package app.clustercontrol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clustercontrol.MainViewModel
import app.clustercontrol.UiState
import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction

private val INTERVALS = listOf(15, 30, 60, 120, 360, 720, 1440)

private fun label(min: Int) = if (min % 60 == 0 && min >= 60) "${min / 60} h" else "$min min"

@Composable
fun ScheduleScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) = Page(modifier, "Schedule") {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Automatismi attivi", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(ui.state.master, { vm.setMaster(it) })
        }
        Text(
            "Il cluster non ha cron: l'app esegue i tick con WorkManager. Il minimo e' 15 minuti e Android (Doze) " +
                "rende i tempi approssimativi: l'esecuzione puo' slittare anche di molto a schermo spento.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (ui.state.autoSuspended) {
        Panel("Automatismi sospesi", error = true) {
            Text(ui.state.suspendReason ?: "")
            Button({ vm.resumeAuto() }) { Text("Riprendi automatismi") }
        }
    }
    ui.schedules.forEach { s ->
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text("${s.action} ogni ${label(s.effectiveIntervalMinutes)}" + if (s.requireCharging) " - solo in carica" else "")
                }
                Switch(s.enabled, { on -> vm.saveSchedules(ui.schedules.map { if (it.id == s.id) s.copy(enabled = on) else it }) })
            }
            Buttons {
                OutlinedButton({ vm.runAction(s.action, s.name) }, enabled = !ui.busy) { Text("Esegui ora") }
                OutlinedButton({ editing = s }) { Text("Modifica") }
                TextButton({ vm.saveSchedules(ui.schedules.filter { it.id != s.id }) }) { Text("Elimina") }
            }
        }
    }
    Button({ editing = Schedule(System.currentTimeMillis().toString(), "", ScheduleAction.TICK, 60, enabled = false) }) { Text("Nuovo schedule") }

    editing?.let { s0 ->
        var s by remember(s0) { mutableStateOf(s0) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Schedule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.name, { s = s.copy(name = it) }, label = { Text("Nome") }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScheduleAction.entries.forEach { FilterChip(s.action == it, { s = s.copy(action = it) }, { Text(it.name) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        INTERVALS.forEach { FilterChip(s.intervalMinutes == it, { s = s.copy(intervalMinutes = it) }, { Text(label(it)) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(s.requireCharging, { s = s.copy(requireCharging = it) })
                        Text("  Solo in carica")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val named = s.copy(name = s.name.ifBlank { "${s.action} ogni ${label(s.intervalMinutes)}" })
                    vm.saveSchedules(if (ui.schedules.any { it.id == named.id }) ui.schedules.map { if (it.id == named.id) named else it } else ui.schedules + named)
                    editing = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Annulla") } },
        )
    }
}
