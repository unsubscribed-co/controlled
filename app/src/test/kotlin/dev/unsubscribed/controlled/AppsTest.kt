package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsTest {
    private val apps = listOf("netflix" to "Netflix", "youtube" to "YouTube", "prime" to "Prime Video", "disney" to "Disney+", "twitch" to "Twitch", "kanto" to "Kanto")
        .map { (id, title) -> TvApp(id, title, null) }

    private fun ids(list: List<TvApp>) = list.map { it.id }

    @Test fun pinsKeepTheirOrderAndLeaveTheRest() {
        val s = sections(apps, pins = listOf("twitch", "netflix"), uses = mapOf("netflix" to 9, "youtube" to 3, "prime" to 7, "kanto" to 1))
        assertEquals(listOf("twitch", "netflix"), ids(s.pinned))
        assertEquals(listOf("prime", "youtube"), ids(s.frequent))
        // One launch (an accidental open) isn't "most used" yet.
        assertEquals(listOf("disney", "kanto"), ids(s.rest))
    }

    @Test fun aPinForAnAppThatIsGoneIsSkipped() {
        assertEquals(listOf("kanto"), ids(sections(apps, listOf("uninstalled", "kanto"), emptyMap()).pinned))
    }

    @Test fun dockFillsWithTheTvOrder() {
        assertEquals(listOf("kanto", "prime", "netflix", "youtube", "disney"), ids(dock(apps, listOf("kanto"), mapOf("prime" to 2, "twitch" to 1))))
        assertEquals(listOf("netflix", "youtube"), ids(dock(apps, emptyList(), emptyMap(), size = 2)))
    }
}
