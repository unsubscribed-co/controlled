package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PcTest {
    private val spanish = "ñÑáéíóúÁÉÍÓÚüÜ¿¡°¬´¨"

    /**
     * Every printable ASCII character and the Spanish ones type. The table is also written to build/keymap.tsv, which
     * tools/check-keymap.ps1 replays through Windows' own Latin American layout (2026-10-26: all match).
     */
    @Test fun everyCharacterTypes() {
        val chars = (0x20..0x7E).map { it.toChar() } + spanish.toList() + listOf('\n', '\t')
        val rows = chars.map { c ->
            val strokes = Keymap.strokes(c) ?: error("no strokes for '$c'")
            "${c.code}\t" + strokes.joinToString(" ") { "${it.mods}:${it.key}" }
        }
        File("build").mkdirs()
        File("build/keymap.tsv").writeText(rows.joinToString("\n"))
    }

    @Test fun accentsUseTheDeadKeyThenTheVowel() {
        assertEquals(listOf(Stroke(0, 0x2F), Stroke(0, 0x04)), Keymap.strokes('á'))
        assertEquals(listOf(Stroke(0, 0x33)), Keymap.strokes('ñ'))
        assertEquals(listOf(Stroke(Keymap.ALT_GR, 0x14)), Keymap.strokes('@'))
    }

    @Test fun reportsPackAsTheDescriptorSays() {
        assertArrayEquals(byteArrayOf(2, 0, 0x04, 0, 0, 0, 0, 0), Hid.keyboard(Keymap.SHIFT, 0x04))
        // 16-bit little-endian x and y: -5 = FB FF, 300 = 2C 01.
        assertArrayEquals(byteArrayOf(1, -5, -1, 0x2C, 1, 0, 0), Hid.mouse(Hid.LEFT, -5, 300))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), Hid.consumer(Hid.VOLUME_UP))
    }

    @Test fun theDescriptorDeclaresThreeReports() {
        val d = Hid.DESCRIPTOR
        val ids = d.indices.filter { d[it] == 0x85.toByte() }.map { d[it + 1].toInt() }
        assertEquals(listOf(Hid.KEYBOARD, Hid.MOUSE, Hid.CONSUMER), ids)
        // Every collection that opens closes.
        assertEquals(d.count { it == 0xA1.toByte() }, d.count { it == 0xC0.toByte() })
    }
}
