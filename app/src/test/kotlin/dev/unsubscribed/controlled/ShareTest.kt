package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTest {
    private fun yt(id: String) = TvLaunch("youtube.leanback.v4", contentId = id)

    @Test fun youtubeLinks() {
        assertEquals(yt("dQw4w9WgXcQ"), shareLaunch("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals(yt("dQw4w9WgXcQ"), shareLaunch("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(yt("dQw4w9WgXcQ"), shareLaunch("Look at this https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(yt("dQw4w9WgXcQ"), shareLaunch("https://youtube.com/live/dQw4w9WgXcQ"))
    }

    @Test fun netflixLinks() {
        val n = TvLaunch("netflix", contentId = "m=https://www.netflix.com/watch/81040344&source_type=4")
        assertEquals(n, shareLaunch("https://www.netflix.com/title/81040344"))
        assertEquals(n, shareLaunch("https://www.netflix.com/co/title/81040344?s=a"))
    }

    @Test fun anythingElseOpensTheBrowser() {
        assertEquals(TvLaunch("com.webos.app.browser", target = "https://unsubscribed.dev/controlled/"),
            shareLaunch("Mira: https://unsubscribed.dev/controlled/."))
        assertEquals("com.webos.app.browser", shareLaunch("https://www.youtube.com/@channel")?.appId)
    }

    @Test fun noLink() {
        assertNull(shareLaunch("just some text"))
    }
}
