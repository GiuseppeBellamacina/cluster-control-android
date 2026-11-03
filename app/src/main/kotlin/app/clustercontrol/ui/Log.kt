package app.clustercontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Link
import app.clustercontrol.UiState

/** The raw log of the active job; the live session refreshes it every few seconds. */
@Composable
fun LogScreen(ui: UiState, modifier: Modifier) {
    val snap = ui.snapshot
    val text = if (snap?.active == null) "" else snap.logTail.orEmpty().trimEnd()
    val scroll = rememberScrollState()
    // follow the end of the log whenever it grows
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Log") {
            val live = ui.link == Link.ONLINE
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(7.dp).background(if (live) C.Green else C.Faint, CircleShape))
                Text(if (live) "Live" else "Offline", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (live) C.GreenText else C.Muted)
            }
        }
        Card(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Caption(if (snap == null) "Nessun dato" else "Nessun job attivo")
            } else {
                Text(text, Modifier.fillMaxWidth().verticalScroll(scroll), style = Mono)
            }
        }
    }
}
