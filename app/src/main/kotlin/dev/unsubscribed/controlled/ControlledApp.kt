package dev.unsubscribed.controlled

import android.app.Application
import android.content.Context

/** One [RemoteModel] for the process: the app's screens and the home screen widget share the same link to the TV. */
class ControlledApp : Application() {
    val model by lazy { RemoteModel(this) }
}

val Context.model get() = (applicationContext as ControlledApp).model
