package app.clustercontrol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.clustercontrol.core.MAX_RESUMES
import app.clustercontrol.core.describeError
import app.clustercontrol.core.parseJobError
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.core.Point
import app.clustercontrol.core.RunSample
import app.clustercontrol.core.RunStatusView
import java.util.Locale

/** Compact number: 4 decimals, scientific for tiny or huge values, no trailing zeros. */
fun fmtNum(v: Double): String = when {
    v == 0.0 -> "0"
    kotlin.math.abs(v) < 1e-3 || kotlin.math.abs(v) >= 1e5 -> "%.2e".format(Locale.ROOT, v)
    else -> "%.4f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

/** `gloss_order_reward` -> `gloss order`. */
fun nice(name: String) = name.removeSuffix("_reward").replace('_', ' ')

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Reward of the RL run: the headline (latest, running mean, best) and one line with a chart per component. */
@Composable
fun RewardsCard(view: RunStatusView, series: Map<String, List<Point>>) {
    val parts = view.rewardComponents
    val headline = view.latest["reward"]
    if (parts.isEmpty() && headline == null) return
    Card {
        Caption("Reward")
        headline?.let {
            Text(fmtNum(it), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val extra = listOfNotNull(
                view.mean["reward"]?.let { "media ${fmtNum(it)}" },
                view.best["reward"]?.let { "migliore ${fmtNum(it)}" },
                view.latest["reward_std"]?.let { "dev. std ${fmtNum(it)}" },
            )
            if (extra.isNotEmpty()) Caption(extra.joinToString(" · "))
        }
        parts.forEach { (name, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KeyValue(nice(name), fmtNum(value))
                series["rewards/$name"]?.takeIf { it.size > 1 }?.let { Sparkline(it, C.Green, Modifier.fillMaxWidth().height(26.dp)) }
            }
        }
    }
}

/** Everything else the job reports (learning rate, gradient norm, KL, lengths, grammar, GPU memory, ...). */
@Composable
fun MetricsCard(view: RunStatusView) {
    val rows = view.otherMetrics.toList() + listOfNotNull(view.gpuMemGb?.let { "GPU mem (GB)" to it })
    if (rows.isEmpty()) return
    Card {
        Caption("Metriche")
        rows.forEach { (k, v) -> KeyValue(k, fmtNum(v)) }
    }
}

/** Final metrics of an evaluation (ROUGE, BLEU, chrF, validity, ...), shown once they are computed. */
@Composable
fun EvalResultsCard(view: RunStatusView) {
    val rows = view.evalResults
    if (rows.isEmpty()) return
    Card {
        Caption("Risultati")
        rows.forEach { (k, v) -> KeyValue(k.replace('_', ' '), fmtNum(v)) }
    }
}

/** The latest completions with the reward of each component: what the model actually produces. */
@Composable
fun SamplesList(samples: List<RunSample>, modifier: Modifier = Modifier) {
    if (samples.isEmpty()) {
        Card(modifier) { Caption("Nessun campione disponibile") }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        samples.forEach { s ->
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (s.match) {
                        true -> Chip("Corretto", Tone.GREEN)
                        false -> Chip("Diverso dal gold", Tone.RED)
                        null -> {}
                    }
                    s.total?.let { Chip("totale ${fmtNum(it)}", Tone.BLUE) }
                }
                s.prompt?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                s.output?.let { Text(it, Modifier.fillMaxWidth(), style = Mono) }
                s.gold?.let { Caption("gold: $it") }
                if (s.rewards.isNotEmpty()) {
                    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        s.rewards.forEach { (k, v) -> KeyValue(nice(k), "%+.2f".format(Locale.ROOT, v)) }
                    }
                }
            }
        }
    }
}

/** A chart for any metric the job reports: pick it from the chips, see its range and latest value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesCard(series: Map<String, List<Point>>) {
    val keys = series.filterValues { it.size > 1 }.keys.toList()
    if (keys.isEmpty()) return
    var chosen by rememberSaveable { mutableStateOf(keys.first()) }
    val key = if (chosen in keys) chosen else keys.first()
    val pts = series.getValue(key)
    Card {
        Caption("Grafico")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys.forEach { k ->
                Box(Modifier.clickable { chosen = k }) { Chip(k, if (k == key) Tone.BLUE else Tone.GREY) }
            }
        }
        Sparkline(pts, C.Accent, Modifier.fillMaxWidth().height(110.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("min ${fmtNum(pts.minOf { it.value })}")
            Caption("ultimo ${fmtNum(pts.last().value)} (step ${pts.last().step})")
            Caption("max ${fmtNum(pts.maxOf { it.value })}")
        }
    }
}

/** The cluster's recent job errors: what hit the job and whether the chain put it back with --resume. */
@Composable
fun ErrorsCard(tail: List<String>, total: Int) {
    val errors = tail.mapNotNull(::parseJobError).takeLast(3).reversed()
    Card {
        Caption(if (total > errors.size) "Errori recenti ($total in tutto)" else "Errori recenti")
        if (errors.isEmpty()) Caption("$total errori registrati sul cluster")
        errors.forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(describeError(e.errorType), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (e.willRetry) Chip("ripreso ${e.retryNum}/${MAX_RESUMES}", Tone.BLUE) else Chip("fermo", Tone.RED)
                }
                Caption("${e.tag} · ${e.jobType} · ${e.timestamp}")
            }
        }
    }
}
