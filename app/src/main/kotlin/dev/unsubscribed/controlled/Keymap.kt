package dev.unsubscribed.controlled

// One key press: HID modifier bits + a Keyboard-page usage.
data class Stroke(val mods: Int, val key: Int)

/** Characters to HID key presses for a PC on the Spanish (Latin America) layout (KLID 0000080A). shortcut: one layout; add US and others when someone needs them. */
object Keymap {
    const val CTRL = 0x01; const val SHIFT = 0x02; const val ALT = 0x04; const val GUI = 0x08; const val ALT_GR = 0x40
    // Named usages used elsewhere:
    const val ENTER = 0x28; const val ESC = 0x29; const val BACKSPACE = 0x2A; const val TAB = 0x2B; const val SPACE = 0x2C
    const val RIGHT = 0x4F; const val LEFT = 0x50; const val DOWN = 0x51; const val UP = 0x52; const val DELETE = 0x4C
    /** The key for a lowercase letter a-z (HID usages run a = 0x04 to z = 0x1D). */
    fun letter(c: Char) = 0x04 + (c - 'a')
    const val HOME = 0x4A; const val END = 0x4D; const val PAGE_UP = 0x4B; const val PAGE_DOWN = 0x4E; const val F4 = 0x3D; const val F5 = 0x3E

    // Dead key stroke definitions on Latin American layout
    private val DEAD_ACUTE = Stroke(0, 0x2F)          // US '[': unshifted is dead acute (´)
    private val DEAD_DIAERESIS = Stroke(SHIFT, 0x2F)  // US '[': shifted is dead diaeresis (¨)
    private val DEAD_GRAVE = Stroke(ALT_GR, 0x31)      // US '\': AltGr is dead grave (`)
    private val DEAD_CIRCUMFLEX = Stroke(ALT_GR, 0x34) // US '\'': AltGr is dead circumflex (^)

    private val DIRECT_MAP: Map<Char, Stroke> = buildMap {
        // Whitespace and control characters
        put(' ', Stroke(0, SPACE))
        put('\n', Stroke(0, ENTER))
        put('\r', Stroke(0, ENTER))
        put('\t', Stroke(0, TAB))

        // Lowercase letters a-z (HID usages 0x04..0x1D)
        for (i in 0 until 26) {
            put(('a'.code + i).toChar(), Stroke(0, 0x04 + i))
        }

        // Uppercase letters A-Z (HID usages 0x04..0x1D with Shift)
        for (i in 0 until 26) {
            put(('A'.code + i).toChar(), Stroke(SHIFT, 0x04 + i))
        }

        // Digits 1-9 (HID 0x1E..0x26) and 0 (HID 0x27)
        for (i in 1..9) {
            put(('0'.code + i).toChar(), Stroke(0, 0x1E + (i - 1)))
        }
        put('0', Stroke(0, 0x27))

        // US '`' key (HID 0x35): base '|', shift '°', AltGr '¬'
        put('|', Stroke(0, 0x35))
        put('°', Stroke(SHIFT, 0x35))
        put('¬', Stroke(ALT_GR, 0x35))

        // Number row shift symbols (keys 0x1E..0x27)
        put('!', Stroke(SHIFT, 0x1E))
        put('"', Stroke(SHIFT, 0x1F))
        put('#', Stroke(SHIFT, 0x20))
        put('$', Stroke(SHIFT, 0x21))
        put('%', Stroke(SHIFT, 0x22))
        put('&', Stroke(SHIFT, 0x23))
        put('/', Stroke(SHIFT, 0x24))
        put('(', Stroke(SHIFT, 0x25))
        put(')', Stroke(SHIFT, 0x26))
        put('=', Stroke(SHIFT, 0x27))

        // US '-' key (HID 0x2D): base '\'', shift '?', AltGr '\'
        put('\'', Stroke(0, 0x2D))
        put('?', Stroke(SHIFT, 0x2D))
        put('\\', Stroke(ALT_GR, 0x2D))

        // US '=' key (HID 0x2E): base '¿', shift '¡'
        put('¿', Stroke(0, 0x2E))
        put('¡', Stroke(SHIFT, 0x2E))

        // US 'Q' key (HID 0x14): AltGr '@'
        put('@', Stroke(ALT_GR, 0x14))

        // US ']' key (HID 0x30): base '+', shift '*', AltGr '~' (not a dead key here, checked against Windows)
        put('+', Stroke(0, 0x30))
        put('*', Stroke(SHIFT, 0x30))
        put('~', Stroke(ALT_GR, 0x30))

        // US ';' key (HID 0x33): base 'ñ', shift 'Ñ'
        put('ñ', Stroke(0, 0x33))
        put('Ñ', Stroke(SHIFT, 0x33))

        // US '\'' key (HID 0x34): base '{', shift '['
        put('{', Stroke(0, 0x34))
        put('[', Stroke(SHIFT, 0x34))

        // US '\' key (HID 0x31): base '}', shift ']'
        put('}', Stroke(0, 0x31))
        put(']', Stroke(SHIFT, 0x31))

        // Non-US ISO extra key left of Z (HID 0x64): base '<', shift '>'
        put('<', Stroke(0, 0x64))
        put('>', Stroke(SHIFT, 0x64))

        // US ',' key (HID 0x36): base ',', shift ';'
        put(',', Stroke(0, 0x36))
        put(';', Stroke(SHIFT, 0x36))

        // US '.' key (HID 0x37): base '.', shift ':'
        put('.', Stroke(0, 0x37))
        put(':', Stroke(SHIFT, 0x37))

        // US '/' key (HID 0x38): base '-', shift '_'
        put('-', Stroke(0, 0x38))
        put('_', Stroke(SHIFT, 0x38))
    }

    private val DEAD_MAP: Map<Char, List<Stroke>> = buildMap {
        fun pair(dead: Stroke, follower: Char): List<Stroke> =
            listOf(dead, DIRECT_MAP.getValue(follower))

        // Standalone dead-key accents typed by following the dead key with Space
        put('´', pair(DEAD_ACUTE, ' '))
        put('¨', pair(DEAD_DIAERESIS, ' '))
        put('^', pair(DEAD_CIRCUMFLEX, ' '))
        put('`', pair(DEAD_GRAVE, ' '))

        // Spanish acute accented vowels typed via dead acute key (US '[') + vowel
        put('á', pair(DEAD_ACUTE, 'a'))
        put('é', pair(DEAD_ACUTE, 'e'))
        put('í', pair(DEAD_ACUTE, 'i'))
        put('ó', pair(DEAD_ACUTE, 'o'))
        put('ú', pair(DEAD_ACUTE, 'u'))
        put('Á', pair(DEAD_ACUTE, 'A'))
        put('É', pair(DEAD_ACUTE, 'E'))
        put('Í', pair(DEAD_ACUTE, 'I'))
        put('Ó', pair(DEAD_ACUTE, 'O'))
        put('Ú', pair(DEAD_ACUTE, 'U'))

        // Spanish diaeresis vowels typed via dead diaeresis key (Shift + US '[') + vowel
        put('ü', pair(DEAD_DIAERESIS, 'u'))
        put('Ü', pair(DEAD_DIAERESIS, 'U'))
    }

    // The strokes that type [c] on the Latin American layout, in order (dead-key accents take two), or null if it can't be typed.
    fun strokes(c: Char): List<Stroke>? {
        DIRECT_MAP[c]?.let { return listOf(it) }
        return DEAD_MAP[c]
    }
}
