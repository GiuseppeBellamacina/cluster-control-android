package app.clustercontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.clustercontrol.MainViewModel
import app.clustercontrol.UiState
import app.clustercontrol.core.Point
import app.clustercontrol.core.downsample
import app.clustercontrol.progressOf
import app.clustercontrol.resultMetrics

@Composable
fun MonitorScreen(ui: UiState, vm: MainViewModel, modifier: Modifier) = Page(modifier, "Monitor") {
    val snap = ui.snapshot
    ui.error?.let { Panel(error = true) { Text(it.message) } }
    var tag by remember(snap?.active?.name) { mutableStateOf(snap?.active?.name?.substringAfter('-').orEmpty()) }
    var token by remember { mutableStateOf("") }
    Buttons {
        Act("Aggiorna log", ui) { vm.refresh() }
        Act("Grafici", ui) { vm.loadSeries(tag.trim()) }
    }
    if (snap?.active == null) Text("Nessun job attivo")
    snap?.let {
        val p = progressOf(it)
        p.fraction?.let { f ->
            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            Text("${p.step}/${p.total}" + (p.eta?.let { e -> "  ETA $e" } ?: ""))
        }
        Panel("Log") {
            Text(
                it.logTail?.takeLast(6000) ?: "(nessun log)",
                Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)),
                style = Mono,
            )
        }
    }
    OutlinedTextField(tag, { tag = it }, Modifier.fillMaxWidth(), label = { Text("Tag per i grafici") }, singleLine = true)
    listOf("loss", "reward").forEach { k ->
        ui.series[k]?.let { Panel(k) { LineChart(downsample(it, 200)) } }
    }
    Panel("Risultati eval") {
        Buttons { Act("Elenco", ui) { vm.loadResultDirs() } }
        ui.resultDirs.forEach { d ->
            Text(d, Modifier.fillMaxWidth().clickable { token = d; vm.loadResults(d) }.padding(vertical = 6.dp), style = Mono)
        }
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Tag o cartella") }, singleLine = true)
        OutlinedButton({ vm.loadResults(token.trim()) }, enabled = !ui.busy && token.isNotBlank()) { Text("Mostra risultati") }
        ui.results.forEach { (run, json) ->
            Text(run, style = MaterialTheme.typography.titleSmall)
            val rows = resultMetrics(json)
            if (rows.isEmpty()) Text("(nessuna metrica nota)", style = Mono)
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(k, Modifier.weight(1f), style = Mono)
                    Text(v, style = Mono)
                }
            }
        }
    }
}

@Composable
private fun LineChart(points: List<Point>) {
    if (points.size < 2) return Text("Dati insufficienti")
    val color = MaterialTheme.colorScheme.primary
    val lo = points.minOf { it.value }
    val hi = points.maxOf { it.value }
    Text("min %.4g  max %.4g  step ${points.first().step}-${points.last().step}".format(lo, hi), style = Mono)
    Canvas(Modifier.fillMaxWidth().height(120.dp).border(1.dp, MaterialTheme.colorScheme.outline)) {
        val x0 = points.first().step.toFloat()
        val dx = (points.last().step - points.first().step).toFloat().coerceAtLeast(1f)
        val dy = (hi - lo).toFloat().coerceAtLeast(1e-9f)
        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset((p.step - x0) / dx * size.width, size.height - ((p.value - lo).toFloat() / dy) * size.height)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
