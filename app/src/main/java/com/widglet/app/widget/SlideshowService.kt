package com.widglet.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.widglet.app.R
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Repo
import com.widglet.app.data.WidgetConfig
import com.widglet.app.render.Renderer
import kotlinx.coroutines.runBlocking

/**
 * Feeds the AdapterViewFlipper used by "Slideshow" timing. The launcher flips through these items itself
 * while the home screen is visible, so Widglet doesn't need to wake up for every change.
 */
class SlideshowService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var cfg = WidgetConfig()
        private var photos: List<Photo> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            // Runs on a binder thread; blocking here is allowed.
            runBlocking {
                val repo = Repo.get(ctx)
                cfg = repo.config(id) ?: WidgetConfig()
                photos = PhotoSelector(ctx).resolve(cfg, repo.state(id).shuffleSeed).photos.take(MAX_ITEMS)
            }
        }

        override fun getCount() = photos.size

        override fun getViewAt(position: Int): RemoteViews {
            val n = photos.size
            val (w, h) = WidgetUpdater.renderSize(ctx, id, if (cfg.clock && cfg.cutout) 2 else 1, cap = 900)
            val list = (0 until minOf(4, n)).map { photos[(position + it) % n] }
            val frame = Renderer(ctx).render(cfg, list, w, h)
            return WidgetUpdater.itemViews(ctx, cfg, frame, h).apply {
                setOnClickFillInIntent(
                    R.id.item_root,
                    Intent().putExtra(WidgetUpdater.EXTRA_URI, photos[position].uri).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                )
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
        override fun onDestroy() = Unit

        companion object {
            const val MAX_ITEMS = 120
        }
    }
}
