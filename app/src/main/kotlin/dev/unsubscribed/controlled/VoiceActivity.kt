package dev.unsubscribed.controlled

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * The widget's mic: Android's own speech dialog over the home screen (no microphone permission here, nothing sent by
 * Controlled), then the phrase goes to the TV ([voiceCommand]). Invisible itself; it closes as soon as the dialog does.
 */
class VoiceActivity : ComponentActivity() {
    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) {
            val model = model
            val t = stringsOf(resources.configuration.locales[0].language)
            // The app's scope, not this activity's: it finishes now, the TV may take a second to connect.
            model.viewModelScope.launch { if (!model.fromHome(HomeKey.SAID + said)) RemoteWidget.toast(applicationContext, t.homeUnreachable) }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val t = stringsOf(resources.configuration.locales[0].language)
        try {
            listen.launch(speechIntent(resources.configuration.locales[0].toLanguageTag(), t.voicePrompt))
        } catch (_: ActivityNotFoundException) {
            RemoteWidget.toast(this, t.noVoice)
            finish()
        }
    }
}

/** Android's speech dialog, offline when the phone can: free, and nothing metered. */
fun speechIntent(language: String, prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    // Spanish and English both: Google's recognizer reads this extra (not in the platform docs; others ignore it).
    .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", when {
        language.startsWith("es") -> arrayOf("en-US")
        language.startsWith("en") -> arrayOf("es-US")
        else -> emptyArray()
    })
