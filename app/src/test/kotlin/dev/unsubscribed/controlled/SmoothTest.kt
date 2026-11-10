package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothTest {
    @Test fun aPcThatSaidNoIsNotAskedAgainByItself() {
        // 2026-11-10: declining on the PC brought the same question back every 20 s with a new code.
        assertEquals(null, smoothRetryMs(SmoothEnd.Declined))
    }

    @Test fun aPcNotFoundYetIsLookedForAgainSoon() {
        assertEquals(20_000L, smoothRetryMs(SmoothEnd.NotFound))
        assertEquals(20_000L, smoothRetryMs(SmoothEnd.Unreachable))
    }

    @Test fun aSessionThatEndedReconnectsAtOnce() {
        assertEquals(0L, smoothRetryMs(SmoothEnd.Ended))
    }
}
