package app.clustercontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Actions
import app.clustercontrol.ErrorKind
import app.clustercontrol.Link
import app.clustercontrol.Op
import app.clustercontrol.UiState
import app.clustercontrol.core.JobNames
import app.clustercontrol.core.Point
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.downsample
import app.clustercontrol.core.formatAgo
import app.clustercontrol.core.formatCountdown
import app.clustercontrol.core.nextTickAt
import app.clustercontrol.core.parseEtaSeconds
import app.clustercontrol.core.percentOf
import app.clustercontrol.progressOf
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(ui: UiState, actions: Actions, modifier: Modifier, goScript: () -> Unit) {
    val now by rememberNow()
    val snap = ui.snapshot
    PullToRefreshBox(ui.refreshing, actions::refresh, modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Dashboard") { LinkStatus(ui, now) }
            ui.error?.let { e ->
                when (e.kind) {
                    ErrorKind.HELPER_MISSING -> Banner(e.message, "Sincronizza", goScript)
                    else -> Banner(e.message, if (ui.link == Link.OFFLINE) "Riprova" else null, actions::refresh)
                }
            }
            JobCard(ui, now)
            val a = snap?.active
            val loss = ui.series["loss"].orEmpty()
            val reward = ui.series["reward"].orEmpty()
            if (a != null && (loss.isNotEmpty() || reward.isNotEmpty())) {
                val p = progressOf(snap)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Loss", p.loss ?: loss.lastOrNull()?.value, loss, C.Accent, Modifier.weight(1f))
                    MetricCard("Reward", p.reward ?: reward.lastOrNull()?.value, reward, C.Green, Modifier.weight(1f))
                }
            }
            TickCard(ui, now)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Tick ora", actions::tick, Modifier.weight(1f), busy = ui.busy)
                Btn(if (snap?.stopped == true) "Riprendi" else "Pausa", actions::pauseResume, Modifier.weight(1f), filled = false, enabled = !ui.busy)
            }
        }
    }
}

@Composable
private fun LinkStatus(ui: UiState, now: Long) {
    val text = when (ui.link) {
        Link.ONLINE -> return
        Link.CONNECTING -> "Connessione"
        Link.OFFLINE -> "Offline"
    }
    val ago = ui.snapshotAt?.let { " · ${formatAgo(now - it)}" }.orEmpty()
    Text(text + ago, fontSize = 12.sp, color = if (ui.link == Link.OFFLINE) C.Red else C.Muted, fontWeight = FontWeight.Medium)
}

private fun grouped(n: Int) = "%,d".format(Locale.ITALY, n)

@Composable
private fun JobCard(ui: UiState, now: Long) {
    val snap = ui.snapshot
    val a = snap?.active
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                a == null -> Chip("Nessun job", Tone.GREY)
                a.state.equals("RUNNING", true) -> Chip("In corso")
                else -> Chip("In attesa", Tone.GREY)
            }
            if (snap?.stopped == true) Chip("Pausa", Tone.RED)
        }
        if (a == null || snap == null) {
            Text("Nessun job in esecuzione", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Caption(if (snap == null) "Nessun dato" else "In coda: ${snap.queue.size}")
            return@Card
        }
        val title = JobNames.parse(a.name)
        val p = progressOf(snap)
        val pct = percentOf(p.step, p.total)
        Column {
            Text(title.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            if (title.subtitle.isNotEmpty()) Caption(title.subtitle)
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(pct?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 60.sp)
            Text("%", Modifier.padding(top = 10.dp, start = 2.dp), fontSize = 22.sp, color = C.Faint, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(9.dp)).background(C.Track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((pct ?: 0) / 100f).background(C.Accent, RoundedCornerShape(9.dp)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(if (p.step != null) "${grouped(p.step)}${p.total?.let { " / ${grouped(it)}" } ?: ""}" else "")
            // counted down every second from the ETA of the last snapshot
            val eta = parseEtaSeconds(p.eta)?.let { it - (now - (ui.snapshotAt ?: now)) / 1000 }
            if (eta != null && eta > 0) Caption("ETA " + formatCountdown(eta * 1000))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Double?, series: List<Point>, color: Color, modifier: Modifier) {
    Card(modifier) {
        Caption(label)
        Text(value?.let { "%.3f".format(Locale.ROOT, it) } ?: "--", fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
        Sparkline(series, color, Modifier.fillMaxWidth().height(38.dp))
    }
}

/** Area fill, faint grid and an emphasised end point. */
@Composable
fun Sparkline(series: List<Point>, color: Color, modifier: Modifier) {
    val pts = downsample(series, 60)
    Canvas(modifier) {
        for (i in 0..2) drawLine(C.Sep, Offset(0f, size.height * i / 2f), Offset(size.width, size.height * i / 2f), 1.dp.toPx())
        if (pts.size < 2) return@Canvas
        val pad = 4.dp.toPx()
        val lo = pts.minOf { it.value }
        val span = (pts.maxOf { it.value } - lo).takeIf { it > 1e-12 } ?: 1.0
        val x0 = pts.first().step.toFloat()
        val xs = (pts.last().step - pts.first().step).toFloat().coerceAtLeast(1f)
        val xy = pts.map {
            Offset(pad + (size.width - 2 * pad) * (it.step - x0) / xs, size.height - pad - (size.height - 2 * pad) * ((it.value - lo) / span).toFloat())
        }
        val line = Path().apply { moveTo(xy[0].x, xy[0].y); xy.drop(1).forEach { lineTo(it.x, it.y) } }
        val area = Path().apply {
            addPath(line)
            lineTo(xy.last().x, size.height)
            lineTo(xy.first().x, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = .12f))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color.copy(alpha = .22f), 7.dp.toPx(), xy.last())
        drawCircle(color, 3.5.dp.toPx(), xy.last())
    }
}

@Composable
private fun TickCard(ui: UiState, now: Long) {
    val last = ui.history.firstOrNull { it.action == Op.TICK.name }
    val interval = ui.schedules.firstOrNull { it.enabled && it.action == ScheduleAction.TICK }?.effectiveIntervalMinutes
    ListCard {
        ListRow {
            Text("Prossimo tick", Modifier.weight(1f), fontSize = 14.sp)
            val text = when {
                interval == null -> "Tick automatico spento"
                else -> nextTickAt(last?.at, interval, now)?.let { (it - now).let { ms -> if (ms <= 0) "ora" else "tra " + formatCountdown(ms) } } ?: "--"
            }
            Text(text, fontSize = 13.sp, color = C.Faint)
        }
        Sep()
        ListRow {
            Text("Ultimo tick", Modifier.weight(1f), fontSize = 14.sp)
            if (last == null) {
                Text("mai", fontSize = 13.sp, color = C.Faint)
            } else {
                Chip(if (last.ok) "OK" else "KO", if (last.ok) Tone.GREEN else Tone.RED)
                Text(formatAgo(now - last.at), fontSize = 13.sp, color = C.Faint)
            }
        }
    }
}
