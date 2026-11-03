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
import app.clustercontrol.StepState
import app.clustercontrol.UiState
import app.clustercontrol.core.formatAgo

val MORE_SECTIONS = listOf("Script", "Connessione", "Storico")

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
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                    }
                }
            }
            return@Column
        }
        Header(sub) { LinkText("‹ Altro") { open(null) } }
        when (sub) {
            "Script" -> ScriptSection(ui, actions)
            "Connessione" -> ConnectionSection(ui, actions)
            else -> HistorySection(ui, actions)
        }
    }
}

@Composable
private fun ScriptSection(ui: UiState, actions: Actions) {
    LaunchedEffect(Unit) { actions.checkScripts() }
    val s = ui.scripts
    ListCard {
        val items = s.items
        if (items == null) {
            ListRow { Caption(if (s.busy) "Controllo..." else "Nessun dato") }
        } else {
            items.forEachIndexed { i, it ->
                if (i > 0) Sep()
                ListRow {
                    Text(it.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (it.upToDate) Chip("aggiornato", Tone.GREEN) else Chip("da aggiornare", Tone.BLUE)
                }
            }
        }
    }
    Btn("Aggiorna", actions::updateScripts, Modifier.fillMaxWidth(), busy = s.busy)
    s.result?.let { Caption(it) }
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
