package dev.unsubscribed.controlled

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** What a widget or bar key sends to [RemoteModel.fromHome]: a key, or a phrase ([SAID] + the words). */
object HomeKey {
    const val POWER = "POWER"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val PLAY_PAUSE = "PLAY_PAUSE"
    // As the LG pointer socket names them, so they pass straight to RemoteModel.button.
    const val BACK = "BACK"
    const val HOME = "HOME"
    const val UP = "UP"
    const val DOWN = "DOWN"
    const val LEFT = "LEFT"
    const val RIGHT = "RIGHT"
    const val ENTER = "ENTER"
    val BUTTONS = setOf(BACK, HOME, UP, DOWN, LEFT, RIGHT, ENTER)
    const val SAID = "said:"
    /** The bar's TV name: the next TV takes the bar and the widget. */
    const val SWITCH = "SWITCH"
}

/**
 * The home screen widget: the notification bar's small remote (power, d-pad, volume, Back, Home, play or pause) and a
 * mic, for the default TV.
 * A tap acts without opening the app: the process-wide model connects, sends, and keeps the link 30 s for the next tap.
 * Nothing runs on a timer; the widget is redrawn when something it shows changes (what's on, a rename).
 */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PRESS) return super.onReceive(context, intent)
        val key = intent.data?.schemeSpecificPart ?: return
        if (key == HomeKey.SWITCH) return context.model.nextHomeTv()
        val done = goAsync()
        val model = context.model
        model.viewModelScope.launch {
            try {
                if (!model.fromHome(key)) toast(context, stringsOf(context.resources.configuration.locales[0].language).homeUnreachable)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val PRESS = "dev.unsubscribed.controlled.PRESS"
        private val KEYS = mapOf(
            R.id.power to HomeKey.POWER, R.id.volume_down to HomeKey.VOLUME_DOWN, R.id.volume_up to HomeKey.VOLUME_UP,
            R.id.play_pause to HomeKey.PLAY_PAUSE, R.id.back to HomeKey.BACK, R.id.home to HomeKey.HOME,
            R.id.up to HomeKey.UP, R.id.down to HomeKey.DOWN, R.id.left to HomeKey.LEFT, R.id.right to HomeKey.RIGHT, R.id.ok to HomeKey.ENTER,
        )

        fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

        /** A home screen press: one PendingIntent per key (the data makes each distinct). */
        fun press(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(PRESS, Uri.fromParts("controlled", key, null), context, RemoteWidget::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun open(context: Context, activity: Class<*>) = PendingIntent.getActivity(
            context, activity.name.hashCode(), Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * Redraws the notification bar and every Controlled widget from what the model knows: the default TV, and what's on
         * while connected.
         */
        fun refresh(context: Context) {
            val model = context.model
            val target = model.controllers.homeTv()
            val now = target?.let { nowOn(context, model, it) }
            RemoteBar.update(context, target?.takeIf { model.controllers.bar }) { layout -> keys(context, layout, target, now) }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RemoteWidget::class.java))
            if (ids.isEmpty()) return
            val t = stringsOf(context.resources.configuration.locales[0].language)
            fun card(layout: Int) = keys(context, layout, target, now).apply {
                if (target == null) setTextViewText(R.id.now_title, t.widgetNoTv)
                // The card opens the app; its keys and the TV name (switching TVs) take their own taps first.
                setOnClickPendingIntent(android.R.id.background, open(context, MainActivity::class.java))
            }
            // The whole remote needs ~270dp; shorter (4x2) gets the compact card instead of a clipped remote (Power and
            // Play or pause were cut off, 2026-11-17). Before Android 12 there's no per-size layout: the compact one fits all.
            val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                SizeF(250f, 110f) to card(R.layout.widget_small),
                SizeF(250f, 270f) to card(R.layout.widget_remote),
            )) else card(R.layout.widget_small)
            manager.updateAppWidget(ids, views)
        }

        /** [layout]'s keys (the widget and the bar share their ids): presses when ready, else they open the app. */
        private fun keys(context: Context, layout: Int, target: PairedTv?, now: Now?): RemoteViews {
            val t = stringsOf(context.resources.configuration.locales[0].language)
            val v = RemoteViews(context.packageName, layout)
            v.setTextViewText(R.id.ok, t.ok)
            if (now != null) {
                v.setTextViewText(R.id.now_title, now.title)
                v.setTextViewText(R.id.now_sub, now.sub.orEmpty())
                v.setViewVisibility(R.id.now_sub, if (now.sub == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (now.icon != null) v.setImageViewBitmap(R.id.now_icon, now.icon)
                v.setViewVisibility(R.id.now_icon, if (now.icon == null) android.view.View.GONE else android.view.View.VISIBLE)
                v.setViewVisibility(R.id.now_tv, if (now.icon == null) android.view.View.VISIBLE else android.view.View.GONE)
            }
            val openApp = open(context, MainActivity::class.java)
            // Without a TV, or before Android 17's local network permission is given, every key opens the app, which asks.
            val ready = target != null && (Build.VERSION.SDK_INT < 37 ||
                context.checkSelfPermission(@Suppress("InlinedApi") Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
            val labels = mapOf(
                R.id.power to t.power, R.id.volume_down to t.volumeDown, R.id.volume_up to t.volumeUp,
                R.id.play_pause to t.playPause, R.id.back to t.back, R.id.home to t.home,
                R.id.up to t.up, R.id.down to t.down, R.id.left to t.left, R.id.right to t.right, R.id.ok to t.ok,
            )
            for ((id, key) in KEYS) {
                v.setContentDescription(id, labels.getValue(id))
                v.setOnClickPendingIntent(id, if (ready) press(context, key) else openApp)
            }
            // More than one TV: the TV's name, marked with the swap symbol, hands the bar to the next one.
            val tvs = context.model.controllers.tvs
            if (target != null && tvs.size > 1) {
                val next = tvs[(tvs.indexOf(target) + 1) % tvs.size]
                v.setTextViewCompoundDrawablesRelative(if (now?.sub != null) R.id.now_sub else R.id.now_title, 0, 0, R.drawable.sym_swap_horiz_small, 0)
                v.setContentDescription(R.id.name, t.switchTo(next.name))
                v.setOnClickPendingIntent(R.id.name, press(context, HomeKey.SWITCH))
            } else {
                v.setContentDescription(R.id.name, t.openRemote)
                v.setOnClickPendingIntent(R.id.name, openApp)
            }
            v.setContentDescription(R.id.mic, t.speak)
            v.setOnClickPendingIntent(R.id.mic, if (ready) open(context, VoiceActivity::class.java) else openApp)
            return v
        }

        /** The bar's first line: what's on and where, while connected; else just the TV's name. */
        class Now(val title: String, val sub: String?, val icon: Bitmap?)

        private fun nowOn(context: Context, model: RemoteModel, tv: PairedTv): Now {
            val id = model.foreground?.takeIf { model.tv?.id == tv.id && model.isConnected && !model.asleep }
            val name = id?.let { model.foregroundName() }
            if (id == null || name == null) return Now(tv.name, null, null)
            val icon = model.icons[id]?.asAndroidBitmap()?.let(::small) ?: monogram(context, TvApp(id, name, null), tv.brand)
            return Now(name, tv.name, icon)
        }

        /** Widget bitmaps count against a small budget: 96 px is sharp at the tile's size. */
        private fun small(b: Bitmap) = if (b.width <= ICON_PX) b else Bitmap.createScaledBitmap(b, ICON_PX, ICON_PX * b.height / b.width, true)

        /** A Google TV gives no icons: the brand's colour and short mark, as on the remote's dock; else the first letter. */
        private fun monogram(context: Context, app: TvApp, brand: Brand): Bitmap {
            val g = if (brand == Brand.GoogleTv) Gtv.app(app.id) else null
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            // Drawn once, shown in light and dark: a fixed dark tile, like an app icon, rather than the theme's colors.
            paint.color = g?.color?.toInt() ?: 0xFF2C2E35.toInt()
            canvas.drawRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), ICON_PX / 5f, ICON_PX / 5f, paint)
            paint.color = g?.onColor?.toInt() ?: 0xFFF2EEE9.toInt()
            paint.textSize = ICON_PX * 0.42f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val mark = g?.mark ?: app.title.take(1).uppercase()
            canvas.drawText(mark, ICON_PX / 2f, ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            return bitmap
        }

        private const val ICON_PX = 96
    }
}
