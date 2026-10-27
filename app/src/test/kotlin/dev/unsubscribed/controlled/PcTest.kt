package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PcTest {
    /** What each layout must type beyond plain ASCII; US has no accents (Keymap skips them). */
    private val extra = mapOf(
        Layout.LatAm to "ñÑáéíóúÁÉÍÓÚüÜ¿¡°¬´¨",
        Layout.Spain to "ñÑáéíóúÁÉÍÓÚüÜ¿¡ºª¬€çÇ´¨",
        Layout.US to "",
    )

    /**
     * Every printable ASCII character types on every layout, and so do its own extras. Each table is also written to
     * build/keymap-<KLID>.tsv, which tools/check-keymap.ps1 replays through Windows' own layout (2026-10-27: all match).
     */
    @Test fun everyCharacterTypes() {
        for (layout in Layout.entries) {
            val chars = (0x20..0x7E).map { it.toChar() } + extra.getValue(layout).toList() + listOf('\n', '\t')
            val rows = chars.map { c ->
                val strokes = Keymap.strokes(c, layout) ?: error("$layout: no strokes for '$c'")
                "${c.code}\t" + strokes.joinToString(" ") { "${it.mods}:${it.key}" }
            }
            File("build").mkdirs()
            File("build/keymap-${layout.klid}.tsv").writeText(rows.joinToString("\n"))
        }
    }

    @Test fun accentsUseTheDeadKeyThenTheVowel() {
        assertEquals(listOf(Stroke(0, 0x2F), Stroke(0, 0x04)), Keymap.strokes('á', Layout.LatAm))
        assertEquals(listOf(Stroke(0, 0x34), Stroke(0, 0x04)), Keymap.strokes('á', Layout.Spain))
        assertEquals(listOf(Stroke(0, 0x33)), Keymap.strokes('ñ', Layout.LatAm))
        assertEquals(listOf(Stroke(Keymap.ALT_GR, 0x14)), Keymap.strokes('@', Layout.LatAm))
        assertEquals(listOf(Stroke(Keymap.SHIFT, 0x1F)), Keymap.strokes('@', Layout.US))
        assertEquals(null, Keymap.strokes('ñ', Layout.US))
    }

    @Test fun theLayoutFollowsThePhonesLanguage() {
        assertEquals(Layout.LatAm, Layout.guess(java.util.Locale("es", "CO")))
        assertEquals(Layout.Spain, Layout.guess(java.util.Locale("es", "ES")))
        assertEquals(Layout.US, Layout.guess(java.util.Locale("en", "US")))
    }

    @Test fun reportsPackAsTheDescriptorSays() {
        assertArrayEquals(byteArrayOf(2, 0, 0x04, 0, 0, 0, 0, 0), Hid.keyboard(Keymap.SHIFT, 0x04))
        // 16-bit little-endian x and y: -5 = FB FF, 300 = 2C 01.
        assertArrayEquals(byteArrayOf(1, -5, -1, 0x2C, 1, 0, 0), Hid.mouse(Hid.LEFT, -5, 300))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), Hid.consumer(Hid.VOLUME_UP))
    }

    @Test fun movesWaitingMergeSoThePointerNeverTrails() {
        val q = Outbox()
        assertEquals(true, q.move(0, 3, 4))   // empty: start sending
        assertEquals(false, q.move(0, 5, -1)) // still waiting: added to it
        assertEquals(false, q.move(0, 2, 0))
        val (id, report) = q.take()
        assertEquals(Hid.MOUSE, id)
        assertArrayEquals(Hid.mouse(0, 10, 3), report)
        assertEquals(true, q.isEmpty)
    }

    @Test fun keysAndButtonsKeepTheirPlaceAndAreNeverMerged() {
        val q = Outbox()
        q.add(Hid.KEYBOARD, Hid.keyboard(0, Keymap.BACKSPACE))
        q.add(Hid.KEYBOARD, Hid.keyboard(0, 0))
        q.add(Hid.KEYBOARD, Hid.keyboard(0, Keymap.BACKSPACE))
        q.add(Hid.KEYBOARD, Hid.keyboard(0, 0))
        q.move(0, 1, 1)
        q.add(Hid.MOUSE, Hid.mouse(Hid.LEFT, 0, 0))
        // A move after the button goes down queues behind it, with the button held, instead of merging into the one before.
        q.move(Hid.LEFT, 2, 2)
        val all = q.takeAll()
        assertEquals(7, all.size)
        assertArrayEquals(Hid.keyboard(0, 0), all[3].second)
        assertArrayEquals(Hid.mouse(0, 1, 1), all[4].second)
        assertArrayEquals(Hid.mouse(Hid.LEFT, 2, 2), all[6].second)
        assertEquals(true, q.isEmpty)
    }

    @Test fun theDescriptorDeclaresThreeReports() {
        val d = Hid.DESCRIPTOR
        val ids = d.indices.filter { d[it] == 0x85.toByte() }.map { d[it + 1].toInt() }
        assertEquals(listOf(Hid.KEYBOARD, Hid.MOUSE, Hid.CONSUMER), ids)
        // Every collection that opens closes.
        assertEquals(d.count { it == 0xA1.toByte() }, d.count { it == 0xC0.toByte() })
    }
}
