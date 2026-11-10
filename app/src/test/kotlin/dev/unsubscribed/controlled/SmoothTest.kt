package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothTest {
    @Test fun aPcThatSaidNoIsNotAskedAgainByItself() {
        // 2026-11-10: declining on the PC brought the same question back every 20 s with a new code.
        assertEquals(null, smoothRetryMs(SmoothEnd.Declined, 0))
    }

    @Test fun aPcNotFoundYetIsLookedForFastThenEvery20Seconds() {
        // 2026-11-10: right after Wi-Fi came back mDNS found nothing, and the next look was 20 s later.
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 20_000L), (0..4).map { smoothRetryMs(SmoothEnd.NotFound, it) })
        assertEquals(2_000L, smoothRetryMs(SmoothEnd.Unreachable, 0))
    }

    @Test fun thePcIsReachedOnItsIpv4Address() {
        // 2026-11-10: mDNS gave the PC's link-local IPv6 first; the program listens on IPv4 only, so the connect timed out.
        val v6 = java.net.InetAddress.getByName("fe80::ee05:9896:8fbb:a1d2")
        val v4 = java.net.InetAddress.getByName("192.168.0.13")
        assertEquals(v4, smoothHost(listOf(v6, v4)))
        assertEquals(null, smoothHost(listOf(v6)))
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
