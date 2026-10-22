package dev.unsubscribed.controlled

/** Dev builds only (BuildConfig.LOG): adb logcat -s Controlled. */
fun log(message: () -> String) {
    if (BuildConfig.LOG) android.util.Log.d("Controlled", message())
}
