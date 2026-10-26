package dev.unsubscribed.controlled

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
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
        if (savedInstanceState == null) shared(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    /** A link from the share sheet goes to the TV; the remote stays open to control what plays. */
    private fun shared(intent: Intent?) {
        // Dev experiment (docs/research/lg-power-on.md): adb shell am start -n <dev app>/<activity> --es ble_wake <16 hex digits>
        if (BuildConfig.LOG) intent?.getStringExtra("ble_wake")?.let { p ->
            val tv = model.tv?.macs?.firstOrNull()?.let(BleWake::bluetoothAddress)
            val prefix = BleWake.hex(p)
            val sent = tv != null && prefix != null && BleWake.send(this, prefix, tv)
            log { "ble wake prefix=$p tv=${tv?.joinToString(":") { "%02x".format(it) }} sent=$sent" }
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val t = stringsOf(resources.configuration.locales[0].language)
        val ok = model.tv != null && model.share(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        Toast.makeText(this, if (model.tv == null) t.pairFirst else if (ok) t.openingOnTv else t.noLink, Toast.LENGTH_SHORT).show()
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
