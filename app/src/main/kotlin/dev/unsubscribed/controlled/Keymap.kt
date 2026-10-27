package dev.unsubscribed.controlled

import kotlinx.serialization.Serializable
import java.util.Locale

// One key press: HID modifier bits + a Keyboard-page usage.
data class Stroke(val mods: Int, val key: Int)

/** The keyboard layout the PC is set to, as Windows names it (KLID): which key types which character. */
@Serializable
enum class Layout(val klid: String) {
    LatAm("0000080A"), Spain("0000040A"), US("00000409");

    companion object {
        /** The phone's language is the best first guess: Spanish in Spain, Spanish elsewhere, anything else. */
        fun guess(locale: Locale = Locale.getDefault()) = when {
            locale.language != "es" -> US
            locale.country == "ES" -> Spain
            else -> LatAm
        }
    }
}

/**
 * Characters to HID key presses for each [Layout]. Every table is checked against Windows' own layout by
 * tools/check-keymap.ps1. Letters sit on the same keys in all three (QWERTY), so shortcuts like Ctrl+C don't depend on it.
 */
object Keymap {
    const val CTRL = 0x01; const val SHIFT = 0x02; const val ALT = 0x04; const val GUI = 0x08; const val ALT_GR = 0x40
    // Named usages used elsewhere:
    const val ENTER = 0x28; const val ESC = 0x29; const val BACKSPACE = 0x2A; const val TAB = 0x2B; const val SPACE = 0x2C
    const val RIGHT = 0x4F; const val LEFT = 0x50; const val DOWN = 0x51; const val UP = 0x52; const val DELETE = 0x4C
    /** The key for a lowercase letter a-z (HID usages run a = 0x04 to z = 0x1D). */
    fun letter(c: Char) = 0x04 + (c - 'a')
    const val HOME = 0x4A; const val END = 0x4D; const val PAGE_UP = 0x4B; const val PAGE_DOWN = 0x4E; const val F4 = 0x3D; const val F5 = 0x3E

    /** The keys every layout shares: whitespace, letters and digits. */
    private fun MutableMap<Char, Stroke>.common() {
        put(' ', Stroke(0, SPACE))
        put('\n', Stroke(0, ENTER))
        put('\r', Stroke(0, ENTER))
        put('\t', Stroke(0, TAB))
        for (i in 0 until 26) {
            put('a' + i, Stroke(0, 0x04 + i))
            put('A' + i, Stroke(SHIFT, 0x04 + i))
        }
        // Digits 1-9 are 0x1E..0x26, 0 is 0x27.
        for (i in 1..9) put('0' + i, Stroke(0, 0x1E + i - 1))
        put('0', Stroke(0, 0x27))
    }

    /** One key's characters: plain, with Shift, with AltGr (null when it types nothing there or is a dead key). */
    private fun MutableMap<Char, Stroke>.key(usage: Int, plain: Char?, shift: Char? = null, altGr: Char? = null) {
        plain?.let { put(it, Stroke(0, usage)) }
        shift?.let { put(it, Stroke(SHIFT, usage)) }
        altGr?.let { put(it, Stroke(ALT_GR, usage)) }
    }

    private val LATAM: Map<Char, Stroke> = buildMap {
        common()
        key(0x35, '|', '°', '¬')
        // Shift on 1..0.
        for (i in 0 until 10) put("!\"#$%&/()="[i], Stroke(SHIFT, 0x1E + i))
        key(0x2D, '\'', '?', '\\')
        key(0x2E, '¿', '¡')
        key(0x14, null, altGr = '@')
        // ~ is a plain AltGr key here, not a dead one (checked against Windows).
        key(0x30, '+', '*', '~')
        key(0x33, 'ñ', 'Ñ')
        key(0x34, '{', '[')
        key(0x31, '}', ']')
        key(0x64, '<', '>')
        key(0x36, ',', ';')
        key(0x37, '.', ':')
        key(0x38, '-', '_')
    }

    private val SPAIN: Map<Char, Stroke> = buildMap {
        common()
        key(0x35, 'º', 'ª', '\\')
        for (i in 0 until 10) put("!\"·$%&/()="[i], Stroke(SHIFT, 0x1E + i))
        key(0x1E, null, altGr = '|')
        key(0x1F, null, altGr = '@')
        key(0x20, null, altGr = '#')
        key(0x23, null, altGr = '¬')
        key(0x08, null, altGr = '€')
        key(0x2D, '\'', '?')
        key(0x2E, '¡', '¿')
        key(0x2F, null, altGr = '[')
        key(0x30, '+', '*', ']')
        key(0x33, 'ñ', 'Ñ')
        key(0x34, null, altGr = '{')
        key(0x31, 'ç', 'Ç', '}')
        key(0x64, '<', '>')
        key(0x36, ',', ';')
        key(0x37, '.', ':')
        key(0x38, '-', '_')
    }

    private val US: Map<Char, Stroke> = buildMap {
        common()
        key(0x35, '`', '~')
        for (i in 0 until 10) put("!@#$%^&*()"[i], Stroke(SHIFT, 0x1E + i))
        key(0x2D, '-', '_')
        key(0x2E, '=', '+')
        key(0x2F, '[', '{')
        key(0x30, ']', '}')
        key(0x31, '\\', '|')
        key(0x33, ';', ':')
        key(0x34, '\'', '"')
        key(0x36, ',', '<')
        key(0x37, '.', '>')
        key(0x38, '/', '?')
    }

    /** The dead keys of a layout (acute, diaeresis, circumflex, grave) and what they type with the next key. */
    private fun deadKeys(direct: Map<Char, Stroke>, acute: Stroke, diaeresis: Stroke, circumflex: Stroke, grave: Stroke): Map<Char, List<Stroke>> = buildMap {
        fun pair(dead: Stroke, follower: Char) = listOf(dead, direct.getValue(follower))
        // A dead key followed by Space types the accent on its own.
        put('´', pair(acute, ' '))
        put('¨', pair(diaeresis, ' '))
        put('^', pair(circumflex, ' '))
        put('`', pair(grave, ' '))
        "aeiouAEIOU".forEachIndexed { i, v -> put("áéíóúÁÉÍÓÚ"[i], pair(acute, v)) }
        put('ü', pair(diaeresis, 'u'))
        put('Ü', pair(diaeresis, 'U'))
    }

    private val TABLES: Map<Layout, Pair<Map<Char, Stroke>, Map<Char, List<Stroke>>>> = mapOf(
        Layout.LatAm to (LATAM to deadKeys(LATAM, acute = Stroke(0, 0x2F), diaeresis = Stroke(SHIFT, 0x2F), circumflex = Stroke(ALT_GR, 0x34), grave = Stroke(ALT_GR, 0x31))),
        // ~ (AltGr+4) is a dead key too in Spain, unlike in Latin America.
        Layout.Spain to (SPAIN to deadKeys(SPAIN, acute = Stroke(0, 0x34), diaeresis = Stroke(SHIFT, 0x34), circumflex = Stroke(SHIFT, 0x2F), grave = Stroke(0, 0x2F)) +
            ('~' to listOf(Stroke(ALT_GR, 0x21), Stroke(0, SPACE)))),
        // shortcut: US has no accents or ñ (Windows types those with Alt codes, which need Num Lock); they're skipped.
        Layout.US to (US to emptyMap()),
    )

    /** The strokes that type [c] on [layout], in order (dead-key accents take two), or null if it can't be typed. */
    fun strokes(c: Char, layout: Layout): List<Stroke>? {
        val (direct, dead) = TABLES.getValue(layout)
        direct[c]?.let { return listOf(it) }
        return dead[c]
    }
}
