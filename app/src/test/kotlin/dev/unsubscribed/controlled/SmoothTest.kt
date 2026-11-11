package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothTest {
    @Test fun aPcThatSaidNoIsNotAskedAgainByItself() {
        // 2026-11-10: declining on the PC brought the same question back every 20 s with a new code.
        assertEquals(null, smoothRetryMs(SmoothEnd.Declined, 0))
    }

    @Test fun aPcNotThereYetIsTriedFastThenEvery10Seconds() {
        // 2026-11-10: reopening the PC program took 21 s to bring smooth mode back; the cap is now 10 s.
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 10_000L, 10_000L), (0..4).map { smoothRetryMs(SmoothEnd.NoProgram, it) })
        assertEquals(2_000L, smoothRetryMs(SmoothEnd.OtherNetwork, 0))
        assertEquals(2_000L, smoothRetryMs(SmoothEnd.NoWifi, 0))
        assertEquals(2_000L, smoothRetryMs(SmoothEnd.Busy, 0))
        assertEquals(2_000L, smoothRetryMs(SmoothEnd.Paused, 0))
    }

    @Test fun aPcThatCantDialInIsOnAnotherNetworkOrOneThatKeepsDevicesApart() {
        // 2026-11-10: a laptop on a second router's Wi-Fi with the same name: 192.168.1.13, the phone 192.168.0.23/24.
        val phone = byteArrayOf(192.toByte(), 168.toByte(), 0, 23)
        assertEquals(false, sameNetwork(phone, 24, byteArrayOf(192.toByte(), 168.toByte(), 1, 13)))
        assertEquals(true, sameNetwork(phone, 24, byteArrayOf(192.toByte(), 168.toByte(), 0, 13)))
        assertEquals(true, sameNetwork(phone, 16, byteArrayOf(192.toByte(), 168.toByte(), 1, 13)))
        assertEquals(false, sameNetwork(phone, 32, byteArrayOf(192.toByte(), 168.toByte(), 0, 24)))
    }

    @Test fun aLongPasteGoesInPiecesThatFitAFrame() {
        // A frame's length is 16 bits: a 70,000-character paste broke the session.
        assertEquals(listOf(8_000, 8_000, 4_000), textPieces("a".repeat(20_000)).map { it.length })
        // An emoji is two chars: a piece never ends between them.
        val emoji = "a".repeat(7_999) + String(Character.toChars(0x1F600)) + "b"
        val pieces = textPieces(emoji)
        assertEquals(emoji, pieces.joinToString(""))
        assertEquals(7_999, pieces[0].length)
        assertEquals(listOf<String>(), textPieces(""))
    }

    @Test fun bluetoothHereLastsWhileWindowsBlocksAndUntilNothingIsHeld() {
        assertEquals(true, bluetoothHere(chosen = true, front = Front.Admin, holding = false))
        assertEquals(true, bluetoothHere(chosen = true, front = Front.Secure, holding = false))
        // The window went away mid-drag: the button's release must go the same way as its press.
        assertEquals(true, bluetoothHere(chosen = true, front = Front.Normal, holding = true))
        assertEquals(false, bluetoothHere(chosen = true, front = Front.Normal, holding = false))
        assertEquals(false, bluetoothHere(chosen = false, front = Front.Admin, holding = false))
    }

    @Test fun theHintComesOnceAndOnlyForWhatWindowsBlocks() {
        // Over an administrator window the pointer still moves; clicks and keys don't.
        assertEquals(false, adminHint(Front.Admin, hinted = false, move = true))
        assertEquals(true, adminHint(Front.Admin, hinted = false, move = false))
        // Ctrl+Alt+Del, the lock screen and the UAC prompt take the pointer too (2026-11-10: it stopped there).
        assertEquals(true, adminHint(Front.Secure, hinted = false, move = true))
        assertEquals(false, adminHint(Front.Secure, hinted = true, move = false))
        assertEquals(false, adminHint(Front.Normal, hinted = false, move = false))
    }

    @Test fun aKeyThatOpensTheSecureScreenCountsAsItUntilThePcSaysOtherwise() {
        assertEquals(Front.Secure, effectiveFront(Front.Normal, expectingSecure = true))
        assertEquals(Front.Secure, effectiveFront(Front.Secure, expectingSecure = false))
        assertEquals(Front.Admin, effectiveFront(Front.Admin, expectingSecure = false))
        assertEquals(Front.Normal, effectiveFront(Front.Normal, expectingSecure = false))
    }

    @Test fun aSessionThatEndedReconnectsAtOnce() {
        assertEquals(0L, smoothRetryMs(SmoothEnd.Ended, 0))
    }
}
