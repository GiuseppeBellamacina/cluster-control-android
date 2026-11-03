package app.clustercontrol.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.clustercontrol.AppSettings
import app.clustercontrol.MainViewModel
import app.clustercontrol.TransportKind
import app.clustercontrol.UiState
import app.clustercontrol.core.ScheduleAction
import java.text.DateFormat
import java.util.Date

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
)

private val SECTIONS = listOf("Script", "Impostazioni", "Lab connessione", "Storico")

@Composable
fun MoreScreen(ui: UiState, vm: MainViewModel, sub: String?, open: (String?) -> Unit, modifier: Modifier) {
    when (sub) {
        "Script" -> ScriptScreen(ui, vm, modifier)
        "Impostazioni" -> SettingsScreen(ui, vm, modifier)
        "Lab connessione" -> LabScreen(ui, vm, modifier)
        "Storico" -> HistoryScreen(ui, modifier)
        else -> Page(modifier, "Altro") {
            SECTIONS.forEach { OutlinedButton({ open(it) }, Modifier.fillMaxWidth()) { Text(it) } }
        }
    }
}

@Composable
private fun ScriptScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) = Page(modifier, "Script") {
    ui.error?.let { Panel(error = true) { Text(it.message) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(ui.settings.includeSrc, { vm.saveSettings(ui.settings.copy(includeSrc = it)) })
        Text("  Includi src/")
    }
    Buttons {
        OutlinedButton({ vm.planSync() }, enabled = !ui.busy) { Text("Controlla differenze") }
        Act("Aggiorna cluster", ui) { vm.runAction(ScheduleAction.SYNC) }
    }
    ui.syncPlan?.let { plan ->
        Panel("Da aggiornare (${plan.size})") {
            if (plan.isEmpty()) Text("Il cluster e' aggiornato")
            plan.forEach { Text(it.remotePath, style = Mono) }
        }
    }
    ui.history.firstOrNull { it.action == ScheduleAction.SYNC.name }?.let {
        Text("Ultimo sync: ${fmt(it.at)} - ${if (it.ok) "ok" else "errore"}: ${it.message}")
    }
}

@Composable
private fun SettingsScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) = Page(modifier, "Impostazioni") {
    var d by remember { mutableStateOf(ui.settings) }
    var pastedKey by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { pastedKey = ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes().take(16_384).toByteArray().toString(Charsets.UTF_8) }.orEmpty() }
    }
    // a key generated or saved elsewhere replaces the draft copy
    LaunchedEffect(ui.settings.privateKey) { d = d.copy(privateKey = ui.settings.privateKey, publicKey = ui.settings.publicKey, passphrase = ui.settings.passphrase) }

    Panel("Cluster") {
        Field("Host", d.host) { d = d.copy(host = it) }
        Field("Utente", d.user) { d = d.copy(user = it) }
        Field("Porta", d.port.toString()) { d = d.copy(port = it.toIntOrNull() ?: d.port) }
    }
    Panel("Chiave SSH") {
        Text(if (d.privateKey.isBlank()) "Nessuna chiave" else "Chiave presente (${d.privateKey.length} caratteri)")
        OutlinedTextField(pastedKey, { pastedKey = it }, Modifier.fillMaxWidth(), label = { Text("Incolla chiave privata") }, minLines = 3, maxLines = 5)
        Buttons {
            OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Da file") }
            OutlinedButton({ vm.generateKey(ui.settings) }, enabled = !ui.busy) { Text("Genera (sostituisce)") }
        }
        Field("Passphrase (opzionale)", d.passphrase, secret = true) { d = d.copy(passphrase = it) }
        if (d.publicKey.isNotBlank()) {
            Text("Chiave pubblica per authorized_keys:")
            Text(d.publicKey, style = Mono)
            OutlinedButton({ clipboard.setText(AnnotatedString(d.publicKey)) }) { Text("Copia") }
        }
    }
    Panel("GitHub") {
        Field("Repo", d.repo) { d = d.copy(repo = it) }
        Field("Branch", d.branch) { d = d.copy(branch = it) }
        Field("Token (opzionale)", d.githubToken, secret = true) { d = d.copy(githubToken = it) }
    }
    Panel("Trasporto") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportKind.entries.forEach { FilterChip(d.transport == it, { d = d.copy(transport = it) }, { Text(it.name) }) }
        }
        if (d.transport == TransportKind.SSTP) {
            Field("Host VPN", d.vpnHost) { d = d.copy(vpnHost = it) }
            Field("Utente VPN", d.vpnUser) { d = d.copy(vpnUser = it) }
            Field("Password VPN", d.vpnPassword, secret = true) { d = d.copy(vpnPassword = it) }
        }
    }
    Buttons {
        Button({
            val key = pastedKey.trim().let { if (it.isNotEmpty()) it + "\n" else null }
            vm.saveSettings(if (key != null) d.copy(privateKey = key, publicKey = "") else d)
            pastedKey = ""
        }) { Text("Salva") }
        OutlinedButton({ vm.forgetHostKey() }) { Text("Dimentica host key") }
    }
}

@Composable
private fun LabScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) = Page(modifier, "Lab connessione") {
    Act("Prova connessione", ui) { vm.runLab() }
    Text(ui.labLines.joinToString("\n"), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), style = Mono)
}

@Composable
private fun HistoryScreen(ui: UiState, modifier: Modifier) = Page(modifier, "Storico") {
    if (ui.history.isEmpty()) Text("Nessuna esecuzione")
    ui.history.forEach {
        Panel(error = !it.ok) {
            Text("${fmt(it.at)}  ${it.source} - ${it.action}  ${if (it.ok) "OK" else "ERRORE"}  ${it.durationMs} ms")
            Text(it.message, style = Mono)
        }
    }
}

private fun fmt(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))
