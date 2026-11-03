package app.clustercontrol.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.clustercontrol.ErrorKind
import app.clustercontrol.MainViewModel
import app.clustercontrol.UiState
import app.clustercontrol.ageText
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.progressOf

@Composable
fun DashboardScreen(ui: UiState, vm: MainViewModel, modifier: Modifier, goScript: () -> Unit) = Page(modifier, "Dashboard") {
    val now = System.currentTimeMillis()
    val snap = ui.snapshot
    if (ui.state.autoSuspended) {
        Panel("Automatismi sospesi", error = true) {
            Text(ui.state.suspendReason ?: "Autenticazione fallita")
            Button({ vm.resumeAuto() }) { Text("Riprendi automatismi") }
        }
    }
    ui.error?.let { e ->
        Panel("Errore", error = true) {
            Text(e.message)
            if (e.kind == ErrorKind.HELPER_MISSING) TextButton(goScript) { Text("Vai a Script") }
        }
    }
    Panel("Stato catena") {
        if (snap == null) {
            Text("Nessun dato: premi Aggiorna.")
        } else {
            Text(if (snap.stopped) "In pausa" else "Attiva")
            val a = snap.active
            if (a == null) {
                Text("Nessun job in esecuzione")
            } else {
                Text("${a.name} (${a.id}) - ${a.state}")
                val p = progressOf(snap)
                p.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    listOfNotNull(
                        p.phase,
                        if (p.step != null) "step ${p.step}${p.total?.let { "/$it" } ?: ""}" else null,
                        p.loss?.let { "loss %.4f".format(it) },
                        p.reward?.let { "reward %.3f".format(it) },
                        p.eta?.let { "ETA $it" },
                    ).joinToString("  "),
                )
            }
            Text("Coda: ${snap.queue.size} job")
            snap.lastJob?.let { Text("Ultimo job: ${it.tag} (${it.id})") }
            if (snap.errorsCount > 0) {
                Text("Errori catena: ${snap.errorsCount}")
                snap.errorsTail.takeLast(3).forEach { Text(it.take(160), style = Mono) }
            }
        }
        Text("Dati: ${ageText(now, ui.state.snapshotAt)}")
        ui.history.firstOrNull { it.action == ScheduleAction.TICK.name }?.let {
            Text("Ultimo tick: ${ageText(now, it.at)} - ${if (it.ok) "ok" else "errore"}: ${it.message}")
        }
    }
    Buttons {
        Act("Tick ora", ui) { vm.runAction(ScheduleAction.TICK) }
        Act(if (snap?.stopped == true) "Riprendi" else "Pausa", ui) { vm.pauseResume() }
        OutlinedButton({ vm.refresh() }, enabled = !ui.busy) { Text("Aggiorna") }
    }
}
