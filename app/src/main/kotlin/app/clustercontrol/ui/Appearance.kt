package app.clustercontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.Actions
import app.clustercontrol.UiState

/** A miniature of a palette: background, card, accent and the two status colours. */
@Composable
private fun Swatch(p: Palette) {
    Row(
        Modifier.background(p.bg, RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, p.hairline), RoundedCornerShape(10.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 18.dp, height = 22.dp).background(p.card, RoundedCornerShape(5.dp)))
        listOf(p.accent, p.green, p.red).forEach { Box(Modifier.size(10.dp).background(it, CircleShape)) }
    }
}

/** The colour themes. "Come il telefono" follows the system's dark mode; the rest are fixed palettes. */
@Composable
fun AppearanceSection(ui: UiState, actions: Actions) {
    val rows = listOf<Pair<String, Palette?>>(Themes.SYSTEM to null) + Themes.all.map { it.id to it }
    ListCard {
        rows.forEachIndexed { i, (id, palette) ->
            if (i > 0) Sep()
            ListRow(onClick = { actions.setTheme(id) }) {
                Swatch(palette ?: Themes.resolve(Themes.SYSTEM, systemDark = androidx.compose.foundation.isSystemInDarkTheme()))
                Column(Modifier.weight(1f)) {
                    Text(palette?.label ?: "Come il telefono", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (palette == null) Caption("Chiaro di giorno, scuro se il telefono è in modalità scura")
                }
                if (ui.theme == id || (palette == null && Themes.all.none { it.id == ui.theme })) {
                    Box(Modifier.size(20.dp).background(C.Accent, CircleShape), contentAlignment = Alignment.Center) {
                        SvgIcon("state-done", C.OnAccent, 12.dp)
                    }
                }
            }
        }
    }
}
