package dev.unsubscribed.controlled

import java.text.Normalizer

/** What a spoken phrase does on the TV. */
sealed interface Voice {
    data class Launch(val app: TvApp) : Voice
    data class Volume(val level: Int) : Voice
    data class Mute(val on: Boolean) : Voice
    data class Input(val input: TvInput) : Voice
    /** Not a command: typed into the TV's open search field, or searched on YouTube. */
    data class Search(val text: String) : Voice
}

/** Words before an app or input name that only mean "open it", in English and Spanish. */
private val opener = Regex("^(open|launch|start|go to|switch to|abre|abrir|abreme|pon|poner|ponme|ir a|ve a|cambia a)\\s+")
private val volume = Regex("^(volume|volumen|vol)\\s+(to\\s+|at\\s+|a\\s+|en\\s+|al\\s+)?(\\d{1,3})$")
private val muteOn = setOf("mute", "silencio", "silenciar", "silencia")
private val muteOff = setOf("unmute", "quitar silencio", "quita el silencio", "quitar el silencio", "con sonido")
// Around a search: "play X in YouTube", "búscame X en YouTube". Matched on the words as said, so the search keeps their case and accents.
private val searchVerb = Regex("^(play|search for|search|look up|find|busca|buscar|búscame|buscame|pon|ponme|reproduce)\\s+", RegexOption.IGNORE_CASE)
private val onYoutube = Regex("\\s+(en|in|on) you ?tube$", RegexOption.IGNORE_CASE)

/** Lowercase, no accents, "+" as "plus": how a phrase and a name are compared. */
private fun plain(s: String) = Normalizer.normalize(s.lowercase().replace("+", " plus"), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

/** Names compare without spaces: the recognizer writes "you tube" and "hdmi2" as often as the TV's own spelling. */
private fun squash(s: String) = s.replace(" ", "")

/**
 * A short fixed grammar over the TV's own app and input names, as `unsubscribed/agent-harness` asks: code, not a model.
 * shortcut: numbers only as digits ("volumen 10"), which is how Android's recognizer writes them; add words if it doesn't.
 */
fun voiceCommand(said: String, apps: List<TvApp>, inputs: List<TvInput>): Voice {
    val p = plain(said)
    volume.find(p)?.let { return Voice.Volume(it.groupValues[3].toInt().coerceIn(0, 100)) }
    if (p in muteOn) return Voice.Mute(true)
    if (p in muteOff) return Voice.Mute(false)
    val name = squash(p.replace(opener, ""))
    apps.firstOrNull { squash(plain(it.title)) == name }?.let { return Voice.Launch(it) }
    inputs.firstOrNull { squash(plain(it.label)) == name }?.let { return Voice.Input(it) }
    val text = said.trim().replace(onYoutube, "").replace(searchVerb, "")
    return Voice.Search(text.ifBlank { said.trim() })
}
