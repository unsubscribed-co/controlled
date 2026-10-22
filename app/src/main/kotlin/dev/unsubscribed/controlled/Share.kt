package dev.unsubscribed.controlled

import java.net.URI

/** What a link shared from the phone opens on the TV: an app with a content id, or the TV's browser at [target]. */
data class TvLaunch(val appId: String, val contentId: String? = null, val target: String? = null)

private val youtubeId = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * "Play on TV": YouTube and Netflix links open in the TV's app at that video; any other link opens in the TV's browser.
 * shortcut: payload shapes from LG community libraries (lgtv2), checked on Juan's TV 2026-10-22; other apps' deep links
 * (Prime Video, Disney+) aren't known yet, so they go to the browser.
 */
fun shareLaunch(text: String): TvLaunch? {
    val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')') ?: return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }

    val video = when (host) {
        "youtu.be" -> segments.firstOrNull()
        "youtube.com", "music.youtube.com" -> when (segments.firstOrNull()) {
            "watch" -> query["v"]
            "shorts", "live", "embed" -> segments.getOrNull(1)
            else -> null
        }
        else -> null
    }
    if (video != null && youtubeId.matches(video)) return TvLaunch("youtube.leanback.v4", contentId = video)

    if (host == "netflix.com") {
        val id = segments.dropWhile { it != "title" && it != "watch" }.getOrNull(1)?.takeIf { it.all(Char::isDigit) }
        if (id != null) return TvLaunch("netflix", contentId = "m=https://www.netflix.com/watch/$id&source_type=4")
    }
    return TvLaunch("com.webos.app.browser", target = url)
}
