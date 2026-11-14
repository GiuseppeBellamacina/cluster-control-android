package app.clustercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import app.clustercontrol.BuildConfig
import app.clustercontrol.ConfigInput
import app.clustercontrol.StepState
import app.clustercontrol.UiState
import app.clustercontrol.core.SyncManifest
import app.clustercontrol.core.formatAgo

val MORE_SECTIONS = listOf("Aspetto", "Sincronizza", "Connessione", "Storico", "Aggiornamenti", "Configurazione", "Sicurezza")

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
                        if (name == "Configurazione" && ui.configMissing.isNotEmpty()) Chip("mancano", Tone.RED)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                    }
                }
            }
            return@Column
        }
        Header(sub) { LinkText("‹ Altro") { open(null) } }
        when (sub) {
            "Aspetto" -> AppearanceSection(ui, actions)
            "Sincronizza" -> SyncSection(ui, actions)
            "Connessione" -> ConnectionSection(ui, actions)
            "Aggiornamenti" -> UpdateSection(ui, actions)
            "Configurazione" -> ConfigSection(ui, actions)
            "Sicurezza" -> SecuritySection(ui, actions)
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
private fun SecuritySection(ui: UiState, actions: Actions) {
    ListCard {
        ListRow {
            Text("Blocco con impronta o PIN", Modifier.weight(1f), fontSize = 15.sp)
            GreenSwitch(ui.lockEnabled) { actions.setLockEnabled(it) }
        }
    }
    Caption(
        if (ui.lockAvailable) "Si riattiva dopo 30 secondi fuori dall'app. I tick in background continuano a funzionare."
        else "Imposta un blocco schermo o un'impronta sul telefono per usarlo.",
    )
}

@Composable
private fun ConfigSection(ui: UiState, actions: Actions) {
    val c = ui.cfg
    // plain remember (keyed on the stored values), not rememberSaveable: secrets must not end up in the saved instance state
    var useVpn by remember(c) { mutableStateOf(c.useVpn) }
    var vpnHost by remember(c) { mutableStateOf(c.vpnHost) }
    var vpnUser by remember(c) { mutableStateOf(c.vpnUser) }
    var vpnPassword by remember(c) { mutableStateOf("") }
    var host by remember(c) { mutableStateOf(c.host) }
    var port by remember(c) { mutableStateOf(c.port) }
    var projectDir by remember(c) { mutableStateOf(c.projectDir) }
    var sshUser by remember(c) { mutableStateOf(c.sshUser) }
    var sshKey by remember(c) { mutableStateOf("") }
    var repo by remember(c) { mutableStateOf(c.repo) }
    var branch by remember(c) { mutableStateOf(c.branch) }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sshKey = ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes().take(16_384).toByteArray().toString(Charsets.UTF_8) }.orEmpty() }
    }
    Caption(if (ui.configMissing.isEmpty()) "Configurazione completa. Tutto è salvato cifrato sul telefono." else "Mancano: " + ui.configMissing.joinToString(", "))

    GroupTitle("Cluster")
    CredField("Host del cluster", host, example = "cluster.example.edu") { host = it }
    CredField("Porta ssh", port, example = "22") { port = it }
    CredField("Cartella del progetto (nella home)", projectDir, example = "myproject") { projectDir = it }
    CredField("Utente SSH", sshUser) { sshUser = it }
    CredField("Chiave privata SSH", sshKey, lines = 4, note = if (c.hasKey) "Salvata: lascia vuoto per mantenerla" else null) { sshKey = it }
    Btn("Scegli file della chiave", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), filled = false)

    GroupTitle("VPN")
    ListCard {
        ListRow {
            Text("Usa VPN (SSTP)", Modifier.weight(1f), fontSize = 15.sp)
            GreenSwitch(useVpn) { useVpn = it }
        }
    }
    if (useVpn) {
        CredField("Host della VPN", vpnHost, example = "vpn.example.edu") { vpnHost = it }
        CredField("Utente VPN", vpnUser) { vpnUser = it }
        CredField("Password VPN", vpnPassword, secret = true, note = if (c.hasVpnPassword) "Salvata: lascia vuoto per mantenerla" else null) { vpnPassword = it }
    }

    GroupTitle("GitHub")
    CredField("Repo del progetto", repo, example = "owner/repo") { repo = it }
    CredField("Branch", branch, example = "main") { branch = it }

    Btn(
        "Salva",
        { actions.saveConfig(ConfigInput(useVpn, vpnHost, vpnUser, vpnPassword, host, port, projectDir, sshUser, sshKey, repo, branch)) },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GroupTitle(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Muted)

@Composable
private fun CredField(
    label: String,
    value: String,
    example: String = "",
    note: String? = null,
    secret: Boolean = false,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        placeholder = if (example.isEmpty()) null else ({ Text(example) }),
        supportingText = note?.let { { Text(it) } },
        singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else 8,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
    )
}

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
    Caption("Configurazione: " + if (ui.configMissing.isEmpty()) "completa" else "incompleta")
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
