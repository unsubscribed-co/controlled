package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectTest {
    @Test fun queueDropsStalePresses() {
        val q = PressQueue<String>()
        q.add(0, "old")
        q.add(900, "fresh")
        assertEquals(listOf("fresh"), q.drain(1_500))
        assertEquals(emptyList<String>(), q.drain(1_500))
    }

    @Test fun queueKeepsTheLastFive() {
        val q = PressQueue<Int>()
        for (i in 1..7) q.add(0, i)
        assertEquals(listOf(3, 4, 5, 6, 7), q.drain(0))
    }

    @Test fun retriesStartFastThenSettle() {
        assertEquals(listOf(0L, 300L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (0..6).map(::retryDelay))
    }
}
