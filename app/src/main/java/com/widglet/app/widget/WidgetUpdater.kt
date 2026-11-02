package com.widglet.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.widglet.app.R
import com.widglet.app.cutout.CutoutStore
import com.widglet.app.cutout.CutoutWorker
import com.widglet.app.cutout.Segmenter
import com.widglet.app.data.AccessLevel
import com.widglet.app.data.ClockPos
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.Order
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoOrdering
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Repo
import com.widglet.app.data.Speed
import com.widglet.app.data.TapAction
import com.widglet.app.data.TimingMode
import com.widglet.app.data.Transition
import com.widglet.app.data.WidgetConfig
import com.widglet.app.data.WidgetLayout
import com.widglet.app.render.Renderer
import com.widglet.app.ui.MainActivity
import com.widglet.app.ui.ViewPhotoActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlin.random.Random

/** Builds and pushes RemoteViews for a widget. All bitmaps are sized to stay well inside the RemoteViews memory limit. */
object WidgetUpdater {
    enum class Action { NEXT, PREV, REFRESH }

    const val EXTRA_URI = "com.widglet.app.URI"
    const val EXTRA_WIDGET_ID = "com.widglet.app.WIDGET_ID"

    private val locks = ConcurrentHashMap<Int, Mutex>()
    /** Last frame per widget, kept so the next update can animate from it. */
    private val lastFrames = ConcurrentHashMap<Int, Renderer.Frame>()

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PhotoWidgetProvider::class.java))

    /** Convenience: re-render without advancing. */
    suspend fun refresh(context: Context, id: Int) = update(context, id, Action.REFRESH)

    suspend fun update(context: Context, id: Int, action: Action, scheduled: Boolean = false, allowInlineCutout: Boolean = true) {
        locks.getOrPut(id) { Mutex() }.withLock { doUpdate(context.applicationContext, id, action, scheduled, allowInlineCutout) }
    }

    private suspend fun doUpdate(ctx: Context, id: Int, action0: Action, scheduled: Boolean, allowInlineCutout: Boolean) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val repo = Repo.get(ctx)
        val cfg = repo.config(id)
        if (cfg == null || !cfg.hasSources) {
            showMessage(ctx, id, ctx.getString(R.string.widget_setup), openAppIntent(ctx, id))
            return
        }
        val gallery = GalleryAccess.get(ctx)
        if (gallery.accessLevel() == AccessLevel.NONE && cfg.pickedUris.isEmpty()) {
            showMessage(ctx, id, ctx.getString(R.string.widget_permission), openAppIntent(ctx, id))
            return
        }
        val app = repo.appNow()
        val st = repo.state(id)
        var action = action0
        if (scheduled) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val saver = cfg.skipInBatterySaver && pm?.isPowerSaveMode == true
            if (app.paused || saver) {
                if (st.currentUri != null && lastFrames.containsKey(id)) return
                action = Action.REFRESH
            }
        }

        if (cfg.timing == TimingMode.SLIDESHOW && !app.paused) {
            pushSlideshow(ctx, id, cfg)
            lastFrames.remove(id)
            return
        }

        val result = PhotoSelector(ctx).resolve(cfg, st.shuffleSeed)
        val list = result.photos
        if (list.isEmpty()) {
            showMessage(ctx, id, ctx.getString(R.string.widget_empty), openAppIntent(ctx, id))
            return
        }
        val uris = list.map { it.uri }
        var seed = st.shuffleSeed
        val idx = when (action) {
            Action.NEXT -> {
                val i = PhotoOrdering.step(uris, st.currentUri, 1)
                // Finished a full shuffled pass: reshuffle next time.
                if (cfg.order == Order.SHUFFLE && i == 0 && st.currentUri != null) seed = Random.nextLong()
                i
            }
            Action.PREV -> PhotoOrdering.step(uris, st.currentUri, -1)
            Action.REFRESH -> uris.indexOf(st.currentUri).takeIf { it >= 0 } ?: 0
        }
        val current = list[idx]

        // Cutout for the photo about to be shown: make it now if missing (one photo, a second or so).
        val store = CutoutStore.get(ctx)
        if (cfg.cutout && allowInlineCutout && store.status(current.uri, cfg.quality) == CutoutStore.Status.MISSING) {
            Segmenter.make(ctx, current.uri, cfg.quality, current.size)
        }
        if (cfg.cutout && cfg.lazyCutouts) {
            val upcoming = (1..cfg.lazyCount).map { uris[(idx + it) % uris.size] }.distinct()
                .filter { store.status(it, cfg.quality) == CutoutStore.Status.MISSING }
            CutoutWorker.enqueueUpcoming(ctx, id, cfg, upcoming)
        }

        val photos: List<Photo> = when (cfg.layout) {
            WidgetLayout.SINGLE -> listOf(current)
            WidgetLayout.STACK -> listOf(current) + st.history.filter { it != current.uri }.mapNotNull { h -> list.firstOrNull { it.uri == h } }.take(2)
                .ifEmpty { (1..2).map { list[(idx + it) % list.size] }.filter { it.uri != current.uri } }
            WidgetLayout.COLLAGE -> (0 until cfg.collageCount.coerceIn(2, 4)).map { list[(idx + it) % list.size] }.distinctBy { it.uri }
        }

        val animate = cfg.transition != Transition.NONE && action != Action.REFRESH
        val layers = if (cfg.clock && cfg.cutout) 2 else 1
        val (w, h) = renderSize(ctx, id, layers * (if (animate) 2 else 1))
        val renderer = Renderer(ctx)
        var prev = if (animate) lastFrames[id] else null
        if (animate && prev == null && st.currentUri != null && st.currentUri != current.uri) {
            list.firstOrNull { it.uri == st.currentUri }?.let { prev = renderer.render(cfg, listOf(it), w, h) }
        }
        val frame = renderer.render(cfg, photos, w, h)

        val rv = RemoteViews(ctx.packageName, flipLayout(cfg.transition, cfg.speed))
        rv.setViewVisibility(R.id.message, View.GONE)
        rv.removeAllViews(R.id.flipper)
        val p = prev
        if (animate && p != null && p.back.width == frame.back.width && p.back.height == frame.back.height) {
            rv.addView(R.id.flipper, itemViews(ctx, cfg, p, h))
            rv.addView(R.id.flipper, itemViews(ctx, cfg, frame, h))
            rv.setDisplayedChild(R.id.flipper, 0)
            rv.showNext(R.id.flipper)
        } else {
            rv.addView(R.id.flipper, itemViews(ctx, cfg, frame, h))
        }
        rv.setOnClickPendingIntent(R.id.root, tapIntent(ctx, id, cfg, current.uri))
        rv.setContentDescription(R.id.root, ctx.getString(R.string.widget_cd, frame.description))
        mgr.updateAppWidget(id, rv)
        lastFrames[id] = frame

        repo.updateState(id) {
            it.copy(
                currentUri = current.uri, history = (listOf(current.uri) + it.history).distinct().take(5),
                shuffleSeed = seed, lastUpdate = System.currentTimeMillis(),
            )
        }
        if (action != Action.REFRESH) repo.updateStats { it.copy(photosShown = it.photosShown + 1) }
        saveThumb(ctx, id, frame.back)
    }

    // ----------------------------------------------------------------- views

    fun itemViews(ctx: Context, cfg: WidgetConfig, frame: Renderer.Frame, h: Int): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_item).apply {
            setImageViewBitmap(R.id.back, frame.back)
            if (frame.front != null) {
                setViewVisibility(R.id.front, View.VISIBLE)
                setImageViewBitmap(R.id.front, frame.front)
            }
            if (cfg.clock) {
                setViewVisibility(R.id.clock_box, View.VISIBLE)
                val g = when (cfg.clockPos) {
                    ClockPos.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    ClockPos.CENTER -> Gravity.CENTER
                    ClockPos.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                setInt(R.id.clock_box, "setGravity", g)
                val timeId = if (cfg.clockMono) R.id.clock_time_mono else R.id.clock_time
                setViewVisibility(R.id.clock_time, if (cfg.clockMono) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.clock_time_mono, if (cfg.clockMono) View.VISIBLE else View.GONE)
                val timePx = (h * 0.22f).coerceIn(24f, 260f)
                setTextViewTextSize(timeId, TypedValue.COMPLEX_UNIT_PX, timePx)
                setTextColor(timeId, cfg.clockColor)
                setViewVisibility(R.id.clock_date, if (cfg.clockDate) View.VISIBLE else View.GONE)
                setTextViewTextSize(R.id.clock_date, TypedValue.COMPLEX_UNIT_PX, (timePx * 0.24f).coerceAtLeast(20f))
                setTextColor(R.id.clock_date, cfg.clockColor)
            }
            setContentDescription(R.id.item_root, frame.description)
        }

    fun flipLayout(t: Transition, s: Speed): Int {
        val i = s.ordinal
        return when (t) {
            Transition.NONE -> R.layout.widget_none
            Transition.CROSSFADE -> arrayOf(R.layout.widget_fade_1, R.layout.widget_fade_2, R.layout.widget_fade_3)[i]
            Transition.SLIDE -> arrayOf(R.layout.widget_slide_1, R.layout.widget_slide_2, R.layout.widget_slide_3)[i]
            Transition.ZOOM -> arrayOf(R.layout.widget_zoom_1, R.layout.widget_zoom_2, R.layout.widget_zoom_3)[i]
            Transition.FLIP -> arrayOf(R.layout.widget_flip_1, R.layout.widget_flip_2, R.layout.widget_flip_3)[i]
            Transition.KEN_BURNS -> arrayOf(R.layout.widget_kb_1, R.layout.widget_kb_2, R.layout.widget_kb_3)[i]
        }
    }

    fun slideshowLayout(t: Transition): Int = when (t) {
        Transition.NONE -> R.layout.slideshow_none
        Transition.CROSSFADE -> R.layout.slideshow_fade
        Transition.SLIDE -> R.layout.slideshow_slide
        Transition.ZOOM -> R.layout.slideshow_zoom
        Transition.FLIP -> R.layout.slideshow_flip
        Transition.KEN_BURNS -> R.layout.slideshow_kb
    }

    private fun pushSlideshow(ctx: Context, id: Int, cfg: WidgetConfig) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val rv = RemoteViews(ctx.packageName, slideshowLayout(cfg.transition))
        val svc = Intent(ctx, SlideshowService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("widglet://slideshow/$id/${cfg.hashCode()}"))
        @Suppress("DEPRECATION")
        rv.setRemoteAdapter(R.id.slideshow, svc)
        rv.setEmptyView(R.id.slideshow, R.id.message)
        rv.setTextViewText(R.id.message, ctx.getString(R.string.widget_empty))
        rv.setInt(R.id.slideshow, "setFlipInterval", cfg.slideshowSeconds.coerceAtLeast(3) * 1000)
        slideshowTemplate(ctx, id, cfg)?.let { rv.setPendingIntentTemplate(R.id.slideshow, it) }
        mgr.updateAppWidget(id, rv)
        @Suppress("DEPRECATION")
        mgr.notifyAppWidgetViewDataChanged(id, R.id.slideshow)
    }

    fun showMessage(ctx: Context, id: Int, text: String, click: PendingIntent?) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_none)
        rv.removeAllViews(R.id.flipper)
        rv.setViewVisibility(R.id.message, View.VISIBLE)
        rv.setTextViewText(R.id.message, text)
        click?.let { rv.setOnClickPendingIntent(R.id.root, it) }
        AppWidgetManager.getInstance(ctx).updateAppWidget(id, rv)
        lastFrames.remove(id)
    }

    fun forget(id: Int) {
        lastFrames.remove(id)
        locks.remove(id)
    }

    // ----------------------------------------------------------------- intents

    private fun flags(mutable: Boolean = false) =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    fun openAppIntent(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id,
        Intent(ctx, MainActivity::class.java).setData(Uri.parse("widglet://open/$id"))
            .putExtra(EXTRA_WIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        flags(),
    )

    private fun tapIntent(ctx: Context, id: Int, cfg: WidgetConfig, uri: String): PendingIntent? = when (cfg.tap) {
        TapAction.NEXT -> PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, TapReceiver::class.java).setAction(TapReceiver.ACTION_TAP)
                .setData(Uri.parse("widglet://tap/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            flags(),
        )
        TapAction.OPEN_PHOTO -> PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, ViewPhotoActivity::class.java).setData(Uri.parse("widglet://photo/$id")).putExtra(EXTRA_URI, uri),
            flags(),
        )
        TapAction.OPEN_APP -> openAppIntent(ctx, id)
        TapAction.NONE -> null
    }

    private fun slideshowTemplate(ctx: Context, id: Int, cfg: WidgetConfig): PendingIntent? = when (cfg.tap) {
        TapAction.NEXT -> PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, TapReceiver::class.java).setAction(TapReceiver.ACTION_SLIDESHOW_NEXT)
                .setData(Uri.parse("widglet://ss/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            flags(mutable = true),
        )
        TapAction.OPEN_PHOTO -> PendingIntent.getActivity(
            ctx, id, Intent(ctx, ViewPhotoActivity::class.java).setData(Uri.parse("widglet://ssphoto/$id")), flags(mutable = true),
        )
        TapAction.OPEN_APP -> PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).setData(Uri.parse("widglet://ssopen/$id")).putExtra(EXTRA_WIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(mutable = true),
        )
        TapAction.NONE -> null
    }

    // ----------------------------------------------------------------- sizing

    /** Widget size in px from launcher options (portrait), defaulting sensibly for launchers that report nothing. */
    fun widgetSizePx(ctx: Context, id: Int): Pair<Int, Int> {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val d = ctx.resources.displayMetrics.density
        var wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        var hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (wDp <= 0) wDp = 180
        if (hDp <= 0) hDp = 180
        return (wDp * d).toInt() to (hDp * d).toInt()
    }

    /**
     * Pick a render size that keeps [bitmaps] bitmaps comfortably below the RemoteViews bitmap budget
     * (screen width x height x 4 bytes x 1.5), and never above 1200px on the long side.
     */
    fun renderSize(ctx: Context, id: Int, bitmaps: Int, cap: Int = 1200): Pair<Int, Int> {
        val (w0, h0) = widgetSizePx(ctx, id)
        val dm = ctx.resources.displayMetrics
        val budget = dm.widthPixels.toLong() * dm.heightPixels * 4 * 3 / 2
        val allowedPx = budget * 0.5 / (4.0 * bitmaps.coerceAtLeast(1))
        var s = minOf(1.0, sqrt(allowedPx / (w0.toDouble() * h0)), cap.toDouble() / maxOf(w0, h0))
        if (s <= 0) s = 1.0
        return (w0 * s).toInt().coerceAtLeast(32) to (h0 * s).toInt().coerceAtLeast(32)
    }

    // ----------------------------------------------------------------- thumbnails (private)

    fun thumbFile(ctx: Context, id: Int) = File(File(ctx.filesDir, "frames").apply { mkdirs() }, "$id.webp")

    private fun saveThumb(ctx: Context, id: Int, b: Bitmap) {
        try {
            val s = 320f / maxOf(b.width, b.height)
            val t = if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
            FileOutputStream(thumbFile(ctx, id)).use { t.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            if (t !== b) t.recycle()
        } catch (_: Exception) {
        }
    }
}
