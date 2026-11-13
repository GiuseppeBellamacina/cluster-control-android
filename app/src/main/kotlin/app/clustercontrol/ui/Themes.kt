package app.clustercontrol.ui

import androidx.compose.ui.graphics.Color

/** Every colour the UI uses. A theme is just one of these: screens only ever read [C]. */
data class Palette(
    val id: String,
    val label: String,
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val ink: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val green: Color,
    val greenText: Color,
    val greenSoft: Color,
    val red: Color,
    val redSoft: Color,
    val muted: Color,
    val faint: Color,
    val sep: Color,
    val track: Color,
    val segBg: Color,
    val segThumb: Color,
    val switchOff: Color,
    val mono: Color,
    val hairline: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

object Themes {
    const val SYSTEM = "system"

    val LIGHT = Palette(
        "light", "Chiaro", false, c(0xF2F2F7), c(0xFFFFFF), c(0x111318), c(0x0A6CFF), c(0xE4F0FF), c(0xFFFFFF),
        c(0x34C759), c(0x1F8A3A), c(0xE1F6E7), c(0xFF3B30), c(0xFDE8E7), c(0x6B7280), c(0x8B909A), c(0xECECF0),
        c(0xE5E5EA), c(0xE6E6EA), c(0xFFFFFF), c(0xD9D9DE), c(0x2A2F3A), c(0xDCDCE2),
    )
    val DARK = Palette(
        "dark", "Scuro", true, c(0x0F1115), c(0x1A1D23), c(0xECEEF2), c(0x4C8DFF), c(0x1E2E4D), c(0xFFFFFF),
        c(0x3DDC84), c(0x5BE08F), c(0x183A27), c(0xFF6B61), c(0x4A2220), c(0x9AA1AD), c(0x6F7683), c(0x2A2E36),
        c(0x2C313A), c(0x262A31), c(0x3A404A), c(0x454B56), c(0xC9CED8), c(0x2A2E36),
    )
    val AMOLED = Palette(
        "amoled", "Nero", true, c(0x000000), c(0x0E0E10), c(0xF2F2F2), c(0x4C8DFF), c(0x14233F), c(0xFFFFFF),
        c(0x3DDC84), c(0x5BE08F), c(0x0F2A1B), c(0xFF6B61), c(0x3A1815), c(0x9A9AA2), c(0x6C6C74), c(0x1E1E22),
        c(0x26262B), c(0x1A1A1E), c(0x34343A), c(0x3A3A40), c(0xCFCFD6), c(0x1E1E22),
    )
    val SEPIA = Palette(
        "sepia", "Seppia", false, c(0xF4ECD8), c(0xFBF6E9), c(0x3B2F1E), c(0xB5651D), c(0xF1DEC4), c(0xFFFFFF),
        c(0x5B8C3A), c(0x3F6B25), c(0xE4EDCF), c(0xC0392B), c(0xF3D9D3), c(0x7A6A52), c(0x9C8D74), c(0xE6DAC0),
        c(0xE0D3B6), c(0xE8DCC2), c(0xFBF6E9), c(0xD8CAAA), c(0x4A3D29), c(0xDDD0B3),
    )
    val OCEAN = Palette(
        "ocean", "Oceano", false, c(0xEAF4F7), c(0xFFFFFF), c(0x0D2A35), c(0x0E7C9B), c(0xD6EEF5), c(0xFFFFFF),
        c(0x2BB673), c(0x17814E), c(0xDDF4E8), c(0xE5484D), c(0xFBE3E4), c(0x5B7480), c(0x87A0AB), c(0xD9E8ED),
        c(0xD0E2E8), c(0xD9E8ED), c(0xFFFFFF), c(0xC5D8DF), c(0x1D3A46), c(0xCFE0E6),
    )
    val FOREST = Palette(
        "forest", "Foresta", true, c(0x0E1512), c(0x16201C), c(0xE4EFE8), c(0x4CC38A), c(0x1C3A2C), c(0x06140D),
        c(0x4CC38A), c(0x6FD9A4), c(0x1C3A2C), c(0xF0716A), c(0x43211F), c(0x93A89D), c(0x6A7F73), c(0x24312B),
        c(0x273530), c(0x202B26), c(0x33433B), c(0x3C4D44), c(0xCFE0D6), c(0x24312B),
    )

    val all = listOf(LIGHT, DARK, AMOLED, SEPIA, OCEAN, FOREST)

    /** The palette for a saved id; "system" (or anything unknown) follows the phone's dark mode. */
    fun resolve(id: String, systemDark: Boolean): Palette =
        all.firstOrNull { it.id == id } ?: if (systemDark) DARK else LIGHT
}
