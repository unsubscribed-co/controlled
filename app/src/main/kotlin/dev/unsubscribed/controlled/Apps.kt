package dev.unsubscribed.controlled

import java.text.Collator

/** An app on the TV: [id] launches it, [icon] is the TV's own icon URL (served on the pinned port, token per connection). */
data class TvApp(val id: String, val title: String, val icon: String?)

/** An HDMI or other input; [appId] is what the TV reports as the foreground app while it's showing. */
data class TvInput(val id: String, val label: String, val appId: String?, val connected: Boolean)

/** The All apps sheet: pinned in the person's order, then most used, then the rest A to Z. */
data class AppSections(val pinned: List<TvApp>, val frequent: List<TvApp>, val rest: List<TvApp>)

/** Launches before an app counts as most used: one accidental open never lands it there. */
const val MOST_USED_MIN = 2

/**
 * Pins never move by themselves, so muscle memory holds; usage only orders what isn't pinned. [apps] is in the TV's own
 * order, which breaks ties among equally used apps.
 */
fun sections(apps: List<TvApp>, pins: List<String>, uses: Map<String, Int>): AppSections {
    val byId = apps.associateBy { it.id }
    val pinned = pins.mapNotNull { byId[it] }
    val others = apps.filter { it.id !in pins }
    val frequent = others.filter { (uses[it.id] ?: 0) >= MOST_USED_MIN }.sortedByDescending { uses[it.id] }
    val collator = Collator.getInstance()
    val rest = (others - frequent.toSet()).sortedWith(compareBy(collator) { it.title })
    return AppSections(pinned, frequent, rest)
}

/** The remote's dock: pinned, then most used, then the TV's own order. */
fun dock(apps: List<TvApp>, pins: List<String>, uses: Map<String, Int>, size: Int = 5): List<TvApp> {
    val s = sections(apps, pins, uses)
    return (s.pinned + s.frequent + apps).distinct().take(size)
}
