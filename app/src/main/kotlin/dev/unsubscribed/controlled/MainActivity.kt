package dev.unsubscribed.controlled

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

class MainActivity : ComponentActivity() {
    private val model: RemoteModel by viewModels()
    /** Android 17's local network permission; always true before it existed. */
    private var allowed by mutableStateOf(false)
    /** Asked once and refused: the rationale offers the system settings instead. */
    private var denied by mutableStateOf(false)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        denied = !granted
        if (granted) model.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val strings = remember(LocalConfiguration.current) { stringsOf(resources.configuration.locales[0].language) }
            CompositionLocalProvider(LocalStrings provides strings) {
                ControlledTheme {
                    if (!allowed) PermissionScreen(denied, ask = { ask.launch(LOCAL_NETWORK) }, settings = ::openAppSettings)
                    else App(model, updateUrl())
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        allowed = Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        if (allowed) model.start()
    }

    override fun onStop() {
        model.stop()
        super.onStop()
    }

    // The phone's volume keys drive the TV while the remote is open and connected (and stay quiet on the phone).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!tvVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) model.volumeUp() else model.volumeDown()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = tvVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun tvVolumeKey(keyCode: Int) = (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
        model.screen == Screen.Remote && model.link == Link.Connected

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /** The dev build's update check: the home page when latest.json (release.sh) is newer. Best effort, null otherwise. */
    @androidx.compose.runtime.Composable
    private fun updateUrl(): String? {
        if (!BuildConfig.SELF_UPDATE) return null
        var url by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val latest = withContext(Dispatchers.IO) {
                runCatching { Json.parseToJsonElement(URL("${BuildConfig.HOME_URL}/latest.json").readText()) }.getOrNull()
            }
            val code = runCatching { latest!!.jsonObject.getValue("versionCode").jsonPrimitive.int }.getOrNull() ?: return@LaunchedEffect
            if (code > BuildConfig.VERSION_CODE) url = "${BuildConfig.HOME_URL}/"
        }
        return url
    }

    private companion object {
        // New in API 37; only asked there.
        @android.annotation.SuppressLint("InlinedApi")
        const val LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}
