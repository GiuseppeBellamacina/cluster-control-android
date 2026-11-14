package app.clustercontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The colours in use. [p] is set by [AppTheme] before the content is composed (a theme change recomposes everything). */
object C {
    var p: Palette = Themes.LIGHT
    val Bg get() = p.bg
    val Card get() = p.card
    val Ink get() = p.ink
    val Accent get() = p.accent
    val AccentSoft get() = p.accentSoft
    val OnAccent get() = p.onAccent
    val Green get() = p.green
    val GreenText get() = p.greenText
    val GreenSoft get() = p.greenSoft
    val Red get() = p.red
    val RedSoft get() = p.redSoft
    val Muted get() = p.muted
    val Faint get() = p.faint
    val Sep get() = p.sep
    val Track get() = p.track
    val SegBg get() = p.segBg
    val SegThumb get() = p.segThumb
    val SwitchOff get() = p.switchOff
    val MonoInk get() = p.mono
    val Hairline get() = p.hairline
}

private fun scheme(p: Palette) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accent, onPrimary = p.onAccent, secondary = p.accent, background = p.bg, onBackground = p.ink,
    surface = p.card, onSurface = p.ink, surfaceVariant = p.bg, onSurfaceVariant = p.muted, error = p.red,
    outline = p.sep, outlineVariant = p.sep, surfaceContainer = p.card, surfaceContainerHigh = p.card,
    surfaceContainerLow = p.card, surfaceContainerHighest = p.card, surfaceContainerLowest = p.card,
)

/** One palette at a time, no dynamic colour. Changing it rebuilds the content so every [C] read is fresh. */
@Composable
fun AppTheme(palette: Palette, content: @Composable () -> Unit) {
    C.p = palette
    androidx.compose.runtime.key(palette.id) { MaterialTheme(colorScheme = scheme(palette), content = content) }
}

val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 16.sp, color = C.MonoInk)

@Composable
fun rememberNow(everyMs: Long = 1000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(everyMs)
        value = System.currentTimeMillis()
    }
}

/** Large bold page title with optional trailing actions. */
@Composable
fun Header(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = C.Ink)
        trailing()
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) =
    TextButton(onClick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.Accent) }

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = C.Card) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** White grouped list; rows are separated by hairlines. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = C.Card) { Column(content = content) }
}

@Composable
fun ListRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Sep() = HorizontalDivider(color = C.Sep, thickness = 1.dp)

enum class Tone(val bg: Color, val fg: Color) {
    BLUE(C.AccentSoft, C.Accent),
    GREEN(C.GreenSoft, C.GreenText),
    RED(C.RedSoft, C.Red),
    GREY(C.Sep, C.Muted),
}

@Composable
fun Chip(text: String, tone: Tone = Tone.BLUE, modifier: Modifier = Modifier) {
    Text(
        text, modifier.background(tone.bg, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tone.fg, maxLines = 1,
    )
}

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick, modifier.height(46.dp), enabled = enabled && !busy, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) C.Accent else C.AccentSoft, contentColor = if (filled) C.OnAccent else C.Accent,
            disabledContainerColor = if (filled) C.Accent.copy(alpha = .5f) else C.AccentSoft, disabledContentColor = if (filled) C.OnAccent else C.Accent.copy(alpha = .5f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (filled) C.OnAccent else C.Accent)
        else Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun GreenSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = C.Green, checkedBorderColor = C.Green,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = C.SwitchOff, uncheckedBorderColor = C.SwitchOff,
    ),
)

/** iOS style segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(C.SegBg, RoundedCornerShape(8.dp)).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)).background(C.SegThumb, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, maxLines = 1) }
        }
    }
}

@Composable
fun Banner(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = C.RedSoft, border = BorderStroke(1.dp, C.Red.copy(alpha = .25f))) {
        Row(Modifier.padding(start = 13.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 13.sp, color = C.Red, fontWeight = FontWeight.Medium)
            if (actionLabel != null) TextButton(onAction) { Text(actionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Red) }
        }
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, fontSize = 12.sp, color = C.Muted, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
