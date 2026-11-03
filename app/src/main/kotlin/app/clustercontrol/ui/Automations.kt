package app.clustercontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Actions
import app.clustercontrol.UiState
import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction

private val INTERVALS = listOf(15, 30, 60, 120, 360, 720, 1440)

private fun short(min: Int) = if (min >= 60 && min % 60 == 0) "${min / 60} h" else "$min m"

private fun long(min: Int) = if (min >= 120 && min % 60 == 0) "${min / 60} h" else "$min min"

private fun autoName(action: ScheduleAction, min: Int) =
    (if (action == ScheduleAction.TICK) "Tick" else "Sync script") + " ogni " + long(min)

private fun caption(action: ScheduleAction) =
    if (action == ScheduleAction.TICK) "Avanza la catena dei job" else "Aggiorna gli script sul cluster"

@Composable
fun AutomationsScreen(ui: UiState, actions: Actions, modifier: Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    fun replace(s: Schedule) = actions.saveSchedules(ui.schedules.map { if (it.id == s.id) s else it })

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Automazioni") { LinkText("Nuova") { adding = true } }
        if (ui.suspended) Banner(ui.suspendReason ?: "Automatismi sospesi", "Riprendi automatismi", actions::resumeAuto)
        ui.schedules.forEach { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Caption(caption(s.action))
                    }
                    GreenSwitch(s.enabled) { replace(s.copy(enabled = it)) }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Altro", tint = C.Faint) }
                        DropdownMenu(menu, { menu = false }, containerColor = Color.White) {
                            DropdownMenuItem(
                                { Text("Elimina", color = C.Red) },
                                { menu = false; actions.saveSchedules(ui.schedules.filter { it.id != s.id }) },
                            )
                        }
                    }
                }
                Segmented(
                    INTERVALS.map(::short), INTERVALS.indexOf(s.effectiveIntervalMinutes),
                    {
                        val renamed = if (s.name == autoName(s.action, s.effectiveIntervalMinutes)) autoName(s.action, INTERVALS[it]) else s.name
                        replace(s.copy(intervalMinutes = INTERVALS[it], name = renamed))
                    },
                )
                Btn("Esegui ora", { actions.runSchedule(s) }, Modifier.fillMaxWidth(), filled = false, enabled = !ui.busy)
            }
        }
    }
    if (adding) NewScheduleSheet({ adding = false }) { actions.saveSchedules(ui.schedules + it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewScheduleSheet(onClose: () -> Unit, onAdd: (Schedule) -> Unit) {
    var action by remember { mutableStateOf(ScheduleAction.TICK) }
    var interval by remember { mutableStateOf(60) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nuova automazione", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Segmented(listOf("Tick", "Sync script"), action.ordinal, { action = ScheduleAction.entries[it] })
            Segmented(INTERVALS.map(::short), INTERVALS.indexOf(interval), { interval = INTERVALS[it] })
            Btn("Aggiungi", {
                onAdd(Schedule(System.currentTimeMillis().toString(), autoName(action, interval), action, interval, enabled = true))
                onClose()
            }, Modifier.fillMaxWidth())
        }
    }
}
