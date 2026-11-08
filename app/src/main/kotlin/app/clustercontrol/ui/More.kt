package app.clustercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Actions
import app.clustercontrol.Op
import app.clustercontrol.Seed
import app.clustercontrol.BuildConfig
import app.clustercontrol.StepState
import app.clustercontrol.UiState
import app.clustercontrol.core.SyncManifest
import app.clustercontrol.core.formatAgo

val MORE_SECTIONS = listOf("Sincronizza", "Connessione", "Storico", "Aggiornamenti")

@Composable
fun MoreScreen(ui: UiState, actions: Actions, sub: String?, open: (String?) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sub == null) {
            Header("Altro")
            ListCard {
                MORE_SECTIONS.forEachIndexed { i, name ->
                    if (i > 0) Sep()
                    ListRow(onClick = { open(name) }) {
                        Text(name, Modifier.weight(1f), fontSize = 15.sp)
                        if (name == "Aggiornamenti" && ui.update.newer) Chip("nuova", Tone.GREEN)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                    }
                }
            }
            return@Column
        }
        Header(sub) { LinkText("‹ Altro") { open(null) } }
        when (sub) {
            "Sincronizza" -> SyncSection(ui, actions)
            "Connessione" -> ConnectionSection(ui, actions)
            "Aggiornamenti" -> UpdateSection(ui, actions)
            else -> HistorySection(ui, actions)
        }
    }
}

@Composable
private fun SyncSection(ui: UiState, actions: Actions) {
    LaunchedEffect(Unit) { actions.checkScripts() }
    val s = ui.scripts
    val st = s.status
    ListCard {
        when {
            st == null -> ListRow { Caption(if (s.busy) "Controllo..." else "Nessun dato") }
            st.upToDate -> ListRow {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(22.dp), tint = C.Green)
                Text("Tutto aggiornato", Modifier.weight(1f), fontSize = 15.sp)
            }
            else -> {
                st.changes.take(MAX_SHOWN).forEachIndexed { i, c ->
                    if (i > 0) Sep()
                    ListRow {
                        Text(c.path, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Chip(if (c.isNew) "nuovo" else "modificato", Tone.BLUE)
                    }
                }
                if (st.changes.size > MAX_SHOWN) {
                    Sep()
                    ListRow { Caption("+ ${st.changes.size - MAX_SHOWN} altri file") }
                }
            }
        }
    }
    val codeWhileRunning = ui.snapshot?.active != null && st?.changes?.any { SyncManifest.isCode(it.path) } == true
    if (codeWhileRunning) Caption("Job in corso: aggiornare src/ cambia il codice mentre gira.")
    if (st != null && !st.upToDate) {
        Btn(if (codeWhileRunning) "Aggiorna comunque (${st.changes.size})" else "Aggiorna ${st.changes.size} file", actions::updateScripts, Modifier.fillMaxWidth(), busy = s.busy)
    } else {
        Btn("Ricontrolla", actions::checkScripts, Modifier.fillMaxWidth(), busy = s.busy)
    }
    s.result?.let { Caption(it) }
}

private const val MAX_SHOWN = 8

@Composable
private fun UpdateSection(ui: UiState, actions: Actions) {
    val u = ui.update
    LaunchedEffect(Unit) { if (!u.checked) actions.checkUpdate() }
    ListCard {
        ListRow {
            Text("Installata", Modifier.weight(1f), fontSize = 15.sp)
            Text(BuildConfig.VERSION_NAME, fontSize = 14.sp, color = C.Faint)
        }
        Sep()
        ListRow {
            Text("Ultima", Modifier.weight(1f), fontSize = 15.sp)
            Text(u.release?.version ?: "-", fontSize = 14.sp, color = C.Faint)
        }
    }
    val progress = u.progress
    when {
        progress != null -> LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Track)
        u.newer -> Btn("Scarica e installa ${u.release?.version}", actions::installUpdate, Modifier.fillMaxWidth())
        else -> Btn("Controlla aggiornamenti", actions::checkUpdate, Modifier.fillMaxWidth(), busy = u.checking)
    }
    if (u.newer) u.release?.notes?.takeIf { it.isNotBlank() }?.let { Caption(it.lines().take(4).joinToString("\n")) }
    if (u.checked && !u.newer && u.error == null) Caption("Sei all'ultima versione.")
    u.error?.let { Caption(it) }
}

@Composable
private fun ConnectionSection(ui: UiState, actions: Actions) {
    Btn("Prova connessione", actions::testConnection, Modifier.fillMaxWidth(), busy = ui.conn.running)
    ListCard {
        val steps = ui.conn.steps.ifEmpty { listOf("VPN", "SSH", "Cluster").map { app.clustercontrol.ConnStep(it) } }
        steps.forEachIndexed { i, st ->
            if (i > 0) Sep()
            ListRow {
                when (st.state) {
                    StepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Accent)
                    StepState.OK -> Icon(Icons.Default.CheckCircle, null, Modifier.size(22.dp), tint = C.Green)
                    StepState.FAIL -> Box(Modifier.size(20.dp).background(C.Red, CircleShape), contentAlignment = Alignment.Center) {
                        Text("!", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    StepState.IDLE -> Box(Modifier.size(20.dp).background(C.Track, CircleShape))
                }
                Column(Modifier.weight(1f)) {
                    Text(st.name, fontSize = 15.sp)
                    st.error?.let { Caption(it) }
                }
                st.ms?.let { Text("$it ms", fontSize = 13.sp, color = C.Faint) }
            }
        }
    }
    Caption("Dati incorporati: " + if (Seed.embedded) "sì" else "no")
}

@Composable
private fun HistorySection(ui: UiState, actions: Actions) {
    var onlyErrors by remember { mutableStateOf(false) }
    val now by rememberNow(30_000)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Segmented(listOf("Tutti", "Errori"), if (onlyErrors) 1 else 0, { onlyErrors = it == 1 }) }
        LinkText("Svuota", actions::clearHistory)
    }
    val rows = ui.history.filter { !onlyErrors || !it.ok }
    ListCard {
        if (rows.isEmpty()) ListRow { Caption("Nessuna operazione") }
        rows.forEachIndexed { i, h ->
            if (i > 0) Sep()
            ListRow {
                Box(Modifier.size(8.dp).background(if (h.ok) C.Green else C.Red, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(Op.entries.firstOrNull { it.name == h.action }?.label ?: h.action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Caption(h.message.ifBlank { h.source }, Modifier.padding(top = 1.dp), maxLines = 2)
                }
                Text(formatAgo(now - h.at), fontSize = 12.sp, color = C.Faint)
            }
        }
    }
}
