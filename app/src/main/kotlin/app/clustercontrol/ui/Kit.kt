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

object C {
    val Bg = Color(0xFFF2F2F7)
    val Card = Color.White
    val Ink = Color(0xFF111318)
    val Accent = Color(0xFF0A6CFF)
    val AccentSoft = Color(0xFFE4F0FF)
    val Green = Color(0xFF34C759)
    val GreenText = Color(0xFF1F8A3A)
    val GreenSoft = Color(0xFFE1F6E7)
    val Red = Color(0xFFFF3B30)
    val RedSoft = Color(0xFFFDE8E7)
    val Muted = Color(0xFF6B7280)
    val Faint = Color(0xFF8B909A)
    val Sep = Color(0xFFECECF0)
    val Track = Color(0xFFE5E5EA)
    val SegBg = Color(0xFFE6E6EA)
}

private val Scheme = lightColorScheme(
    primary = C.Accent, onPrimary = Color.White, secondary = C.Accent, background = C.Bg, onBackground = C.Ink,
    surface = C.Card, onSurface = C.Ink, surfaceVariant = C.Bg, onSurfaceVariant = C.Muted, error = C.Red,
    outline = C.Sep, outlineVariant = C.Sep, surfaceContainer = C.Card, surfaceContainerHigh = C.Card,
    surfaceContainerLow = C.Card, surfaceContainerHighest = C.Card, surfaceContainerLowest = C.Card,
)

/** Light only: no dark theme and no dynamic colour, on purpose. */
@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 16.sp, color = Color(0xFF2A2F3A))

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
            containerColor = if (filled) C.Accent else C.AccentSoft, contentColor = if (filled) Color.White else C.Accent,
            disabledContainerColor = if (filled) C.Accent.copy(alpha = .5f) else C.AccentSoft, disabledContentColor = if (filled) Color.White else C.Accent.copy(alpha = .5f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (filled) Color.White else C.Accent)
        else Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun GreenSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = C.Green, checkedBorderColor = C.Green,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD9D9DE), uncheckedBorderColor = Color(0xFFD9D9DE),
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
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)).background(Color.White, RoundedCornerShape(6.dp)) else Modifier)
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
