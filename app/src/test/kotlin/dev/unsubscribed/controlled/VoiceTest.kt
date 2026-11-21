package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTest {
    private val netflix = TvApp("netflix", "Netflix", null)
    private val youtube = TvApp("youtube.leanback.v4", "YouTube", null)
    private val disney = TvApp("com.disney.disneyplus-prod", "Disney+", null)
    private val apps = listOf(netflix, youtube, disney)
    private val hdmi2 = TvInput("HDMI_2", "HDMI 2", "com.webos.app.hdmi2", connected = true)
    private fun parse(said: String) = voiceCommand(said, apps, listOf(hdmi2))

    @Test fun anAppByItsNameWithOrWithoutAVerb() {
        assertEquals(Voice.Launch(netflix), parse("Netflix"))
        assertEquals(Voice.Launch(netflix), parse("abre Netflix"))
        assertEquals(Voice.Launch(youtube), parse("open you tube"))
        assertEquals(Voice.Launch(disney), parse("pon Disney plus"))
    }

    @Test fun volumeMuteAndInputs() {
        assertEquals(Voice.Volume(10), parse("volumen 10"))
        assertEquals(Voice.Volume(25), parse("Volume to 25"))
        assertEquals(Voice.Volume(100), parse("volume 300"))
        assertEquals(Voice.Mute(true), parse("silencio"))
        assertEquals(Voice.Mute(true), parse("Mute"))
        assertEquals(Voice.Mute(false), parse("quitar silencio"))
        assertEquals(Voice.Input(hdmi2), parse("HDMI 2"))
        assertEquals(Voice.Input(hdmi2), parse("pon hdmi2"))
    }

    @Test fun anythingElseIsASearchAsSaid() {
        assertEquals(Voice.Search("partido de Colombia"), parse(" partido de Colombia "))
        assertEquals(Voice.Search("Netflix documentaries"), parse("Netflix documentaries"))
        assertEquals(Voice.Search("volumen alto"), parse("volumen alto"))
    }

    // Said to the phone on 2026-11-21: the whole sentence went to YouTube's search box.
    @Test fun aSearchDropsTheWordsAroundIt() {
        assertEquals(Voice.Search("crujillos from Nacho Vegas"), parse("Play crujillos from Nacho Vegas in YouTube"))
        assertEquals(Voice.Search("partido de Colombia"), parse("Búscame partido de Colombia en YouTube"))
        assertEquals(Voice.Search("cats"), parse("search for cats"))
        assertEquals(Voice.Search("música para dormir"), parse("pon música para dormir"))
        assertEquals(Voice.Search("Nacho Vegas"), parse("Nacho Vegas on YouTube"))
        assertEquals(Voice.Search("play"), parse("play"))
    }
}
