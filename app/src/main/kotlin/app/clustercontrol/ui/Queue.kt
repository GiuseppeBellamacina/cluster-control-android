package app.clustercontrol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clustercontrol.MainViewModel
import app.clustercontrol.UiState
import app.clustercontrol.core.buildEntry
import app.clustercontrol.core.resolveJobs

@Composable
fun QueueScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) {
    var mode by rememberSaveable { mutableStateOf("list") }
    var confirmKill by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (ui.catalog.isEmpty()) vm.loadCatalog() }
    Page(modifier, "Coda") {
        ui.error?.let { Panel(error = true) { Text(it.message) } }
        if (mode == "add") AddJob(ui, vm) { mode = "list" }
        if (mode == "presets") PresetPicker(ui, vm) { mode = "list" }
        if (mode != "list") {
            TextButton({ mode = "list" }) { Text("Annulla") }
        } else {
            val snap = ui.snapshot
            Buttons {
                Act("Aggiungi", ui) { mode = "add" }
                Act("Preset", ui) { mode = "presets" }
            }
            Buttons {
                Act(if (snap?.stopped == true) "Riprendi" else "Pausa", ui) { vm.pauseResume() }
                OutlinedButton({ confirmKill = true }, enabled = !ui.busy && snap?.active != null) { Text("Kill attivo") }
                OutlinedButton({ vm.submit(emptyList(), replace = true, startNow = false) }, enabled = !ui.busy && snap?.queue?.isNotEmpty() == true) {
                    Text("Svuota")
                }
            }
            snap?.active?.let { Panel("Attivo") { Text("${it.name} (${it.id}) - ${it.state}") } }
            Panel("In coda (${snap?.queue?.size ?: 0})") {
                if (snap?.queue.isNullOrEmpty()) Text("Coda vuota")
                snap?.queue?.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${e.tag}", style = MaterialTheme.typography.titleSmall)
                            Text("${e.type} ${e.config}${e.extra?.let { " $it" } ?: ""}", style = Mono)
                        }
                        IconButton({ vm.removeTag(e.tag) }, enabled = !ui.busy) { Icon(Icons.Default.Delete, "Rimuovi") }
                    }
                }
            }
        }
    }
    if (confirmKill) {
        AlertDialog(
            onDismissRequest = { confirmKill = false },
            title = { Text("Terminare il job attivo?") },
            text = { Text("Il job in corso viene cancellato (scancel). La catena prosegue al prossimo tick.") },
            confirmButton = { TextButton({ confirmKill = false; vm.kill() }) { Text("Termina") } },
            dismissButton = { TextButton({ confirmKill = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun ApplyOptions(replace: Boolean, startNow: Boolean, onReplace: (Boolean) -> Unit, onStart: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(replace, onReplace)
        Text("Sostituisci la coda")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(startNow, onStart)
        Text("Avvia subito")
    }
}

@Composable
private fun AddJob(ui: UiState, vm: MainViewModel, done: () -> Unit) = Panel("Nuovo job") {
    var type by remember { mutableStateOf("train") }
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var startNow by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("train", "eval").forEach { FilterChip(type == it, { type = it }, { Text(it) }) }
    }
    ui.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Cerca config (${ui.catalog.size})") }, singleLine = true)
    ui.catalog.filter { search.isBlank() || it.path.contains(search.trim(), ignoreCase = true) || it.name.contains(search.trim(), ignoreCase = true) }
        .take(8).forEach { c ->
            Text(
                c.name,
                Modifier.fillMaxWidth().clickable { picked = c.path; tag = c.name }.padding(vertical = 6.dp),
                color = if (picked == c.path) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    OutlinedTextField(tag, { tag = it }, Modifier.fillMaxWidth(), label = { Text("Tag") }, singleLine = true)
    OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), label = { Text("Mode (opzionale, es. --resume)") }, singleLine = true)
    ApplyOptions(replace, startNow, { replace = it }, { startNow = it })
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({
        runCatching { buildEntry(type, picked ?: error("Scegli una config"), tag.trim(), extra.trim()) }
            .onSuccess { vm.submit(listOf(it), replace, startNow); done() }
            .onFailure { error = it.message }
    }, enabled = !ui.busy) { Text("Accoda") }
}

@Composable
private fun PresetPicker(ui: UiState, vm: MainViewModel, done: () -> Unit) = Panel("Preset") {
    var order by remember { mutableStateOf(listOf<String>()) }
    var replace by remember { mutableStateOf(false) }
    var startNow by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ui.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (ui.presets.isEmpty()) Text("Nessun preset caricato")
    ui.presets.forEach { p ->
        val pos = order.indexOf(p.id)
        Column(Modifier.fillMaxWidth().clickable { order = if (pos >= 0) order - p.id else order + p.id }.padding(vertical = 4.dp)) {
            Text(
                (if (pos >= 0) "${pos + 1}. " else "") + "${p.label} (${p.jobs.size} job)",
                color = if (pos >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
        }
    }
    ApplyOptions(replace, startNow, { replace = it }, { startNow = it })
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({
        runCatching { resolveJobs(ui.catalog, order.flatMap { id -> ui.presets.first { it.id == id }.jobs }) }
            .onSuccess { vm.submit(it, replace, startNow); done() }
            .onFailure { error = it.message }
    }, enabled = !ui.busy && order.isNotEmpty()) { Text("Applica") }
}
