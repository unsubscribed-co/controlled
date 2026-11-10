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

    @Test fun aSessionThatEndedReconnectsAtOnce() {
        assertEquals(0L, smoothRetryMs(SmoothEnd.Ended, 0))
    }
}
