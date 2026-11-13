package app.clustercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.core.RunStatusView

/** A chip with an optional line icon in front of the text and an optional state icon behind it. */
@Composable
fun IconChip(icon: String?, text: String, tone: Tone = Tone.GREY, state: String? = null) {
    Row(
        Modifier.background(tone.bg, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SvgIcon(icon, tone.fg, 12.dp)
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tone.fg, maxLines = 1)
        SvgIcon(state, tone.fg, 11.dp)
    }
}

/** What the job is: algorithm, model and the free chips it asked for. Nothing here is known to the app in advance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityChips(view: RunStatusView) {
    val chips = buildList<@Composable () -> Unit> {
        view.method?.let { m -> add { IconChip(m, m.uppercase(), Tone.BLUE) } }
        val model = listOfNotNull(view.modelName, view.modelParams?.takeIf { view.modelName?.contains(it, ignoreCase = true) != true })
            .joinToString(" · ")
        if (model.isNotEmpty() || view.modelFamily != null) {
            add { IconChip(view.modelFamily, model.ifEmpty { view.modelFamily.orEmpty() }) }
        }
        view.badges.forEach { b -> add { IconChip(b.icon, b.label) } }
    }
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { it() }
    }
}

/** The planned stages in order, the current one highlighted: SFT (done) › GRPO (running) › Eval. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageStepper(view: RunStatusView) {
    if (view.stages.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        view.stages.forEachIndexed { i, s ->
            if (i > 0) Text("›", fontSize = 14.sp, color = C.Faint, modifier = Modifier)
            val (tone, mark) = when (s.state) {
                "done" -> Tone.GREEN to "state-done"
                "running" -> Tone.BLUE to "state-running"
                "failed" -> Tone.RED to "state-failed"
                else -> Tone.GREY to null
            }
            IconChip(s.icon, s.label, tone, mark)
        }
    }
}
