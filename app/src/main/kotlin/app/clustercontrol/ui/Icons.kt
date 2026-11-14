package app.clustercontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn from SVG path data (24x24 viewBox, 2 unit round stroke). The training only sends an id
 * ("grpo", "llm", "gpu", ...); an id without a picture shows no icon, the text speaks for itself.
 */
private val PATHS = mapOf(
    // what the job does
    "train" to "M6.5 6.5v11 M17.5 6.5v11 M3.5 9v6 M20.5 9v6 M6.5 12h11",
    "eval" to "M5 20V11 M12 20V4 M19 20v-6",
    "inference" to "M4 5h16v11H10l-4 4v-4H4z",
    // method
    "sft" to "M2 9l10-5 10 5-10 5z M6 11.5V16c0 1.5 3 3 6 3s6-1.5 6-3v-4.5",
    "grpo" to "M12 3a9 9 0 1 0 .01 0z M12 8a4 4 0 1 0 .01 0z M12 12h.01",
    "ppo" to "M3 17l5-5 4 4 8-9 M15 7h5v5",
    "dpo" to "M12 4v16 M7 20h10 M5 7h14 M5 7l-3 7a3 3 0 0 0 6 0z M19 7l-3 7a3 3 0 0 0 6 0z",
    "rl" to "M4 12a8 8 0 0 1 14-5l2 2 M20 4v5h-5 M20 12a8 8 0 0 1-14 5l-2-2 M4 20v-5h5",
    "pretrain" to "M12 3L3 8l9 5 9-5z M3 12l9 5 9-5 M3 16l9 5 9-5",
    "finetune" to "M4 6h10 M18 6h2 M4 12h4 M12 12h8 M4 18h12 M20 18h0 M14 4v4 M10 10v4 M16 16v4",
    "distill" to "M9 3h6 M10 3v6l-5 9a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-9V3",
    // what is being trained
    "llm" to "M12 4v3 M5 8h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z M9 13h.01 M15 13h.01 M9 16.5h6",
    "nn" to "M6 7.5a1.5 1.5 0 1 0 .01 0z M6 16.5a1.5 1.5 0 1 0 .01 0z M18 12a1.5 1.5 0 1 0 .01 0z M7.4 8l9.2 3.2 M7.4 16l9.2-3.2",
    "cnn" to "M3 3h18v18H3z M3 9h18 M3 15h18 M9 3v18 M15 3v18",
    "rnn" to "M17 2l4 4-4 4 M3 11V9a3 3 0 0 1 3-3h15 M7 22l-4-4 4-4 M21 13v2a3 3 0 0 1-3 3H3",
    "transformer" to "M13 2L4 14h7l-1 8 9-12h-7z",
    "ml" to "M3 3v18h18 M7 15l4-4 3 3 5-6",
    "tree" to "M12 21V8 M12 8L6 4 M12 13l6-4 M12 17l-4-2",
    "diffusion" to "M3 8c3-3 6 3 9 0s6 3 9 0 M3 15c3-3 6 3 9 0s6 3 9 0",
    "vision" to "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 .01 0z",
    "speech" to "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v4",
    // misc
    "gpu" to "M6 6h12v12H6z M9 9h6v6H9z M9 2v4 M15 2v4 M9 18v4 M15 18v4 M2 9h4 M2 15h4 M18 9h4 M18 15h4",
    "data" to "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
    // stage states
    "state-done" to "M5 12.5l4.5 4.5L19 7.5",
    "state-running" to "M8 5v14l11-7z",
    "state-failed" to "M6 6l12 12 M18 6L6 18",
)

private val ALIASES = mapOf("supervised" to "sft", "rlhf" to "rl", "pretraining" to "pretrain", "reinforcement" to "rl")

/** The SVG path data for an icon id (case and spaces ignored), or null when there is no picture for it. */
fun iconPath(id: String?): String? {
    val key = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return PATHS[ALIASES[key] ?: key]
}

/** Draws [id] as a line icon in [tint]; draws nothing for an unknown id. */
@Composable
fun SvgIcon(id: String?, tint: Color, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val data = iconPath(id) ?: return
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
