package app.clustercontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.core.RunStatusView

private val GLYPHS = mapOf(
    // what the job does
    "train" to "🏋️", "eval" to "📊", "inference" to "💬",
    // method
    "sft" to "🎓", "grpo" to "🎯", "ppo" to "🕹️", "dpo" to "⚖️", "rl" to "🎮", "rlhf" to "🎮",
    "pretrain" to "🧱", "pretraining" to "🧱", "finetune" to "🔧", "distill" to "⚗️", "supervised" to "🎓",
    // what is being trained
    "llm" to "🤖", "nn" to "🧠", "cnn" to "🖼️", "rnn" to "🔁", "transformer" to "⚡", "ml" to "📈",
    "tree" to "🌳", "diffusion" to "🌫️", "vision" to "👁️", "speech" to "🎙️",
    // misc
    "gpu" to "🖥️", "data" to "🗂️",
)

/**
 * A picture for an icon id sent by the training. Known ids map to a glyph, a short non-alphanumeric string (an emoji
 * the training chose itself) is shown as is, anything else gets no picture and the text speaks for itself.
 */
fun glyphFor(id: String?): String? {
    val key = id?.trim()?.lowercase() ?: return null
    GLYPHS[key]?.let { return it }
    return id.trim().takeIf { it.length <= 4 && it.isNotEmpty() && it.none { c -> c.isLetterOrDigit() } }
}

fun withGlyph(icon: String?, text: String): String = glyphFor(icon)?.let { "$it $text" } ?: text

@Composable
fun IconChip(icon: String?, text: String, tone: Tone = Tone.GREY) = Chip(withGlyph(icon, text), tone)

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

/** The planned stages in order, the current one highlighted: `SFT ✓  ›  GRPO ▶  ›  Eval`. */
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
                "done" -> Tone.GREEN to " ✓"
                "running" -> Tone.BLUE to " ▶"
                "failed" -> Tone.RED to " ✗"
                else -> Tone.GREY to ""
            }
            Chip(withGlyph(s.icon, s.label) + mark, tone)
        }
    }
}
