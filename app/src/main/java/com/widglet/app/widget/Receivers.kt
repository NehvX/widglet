package com.widglet.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.widglet.app.R
import com.widglet.app.cutout.CleanupWorker
import com.widglet.app.cutout.CutoutWorker
import com.widglet.app.data.DoubleTapAction
import com.widglet.app.data.Repo
import com.widglet.app.data.TimingMode
import com.widglet.app.data.WidgetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread while keeping the receiver alive (max ~9 s). */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    appScope.launch {
        try {
            withTimeoutOrNull(9_000) { block() }
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}

class PhotoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        async {
            ids.forEach { WidgetUpdater.update(context, it, WidgetUpdater.Action.REFRESH, allowInlineCutout = false) }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        async { WidgetUpdater.update(context, id, WidgetUpdater.Action.REFRESH, allowInlineCutout = false) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val wm = WorkManager.getInstance(context)
        ids.forEach { id ->
            wm.cancelUniqueWork(Scheduler.periodicName(id))
            wm.cancelUniqueWork(CutoutWorker.uniqueName(id))
            WidgetUpdater.forget(id)
            WidgetUpdater.thumbFile(context, id).delete() // our own private file only
        }
        async {
            ids.forEach { Repo.get(context).deleteWidget(it) }
            CleanupWorker.runSoon(context) // drop cutouts no other widget uses
        }
    }

    override fun onEnabled(context: Context) {
        CleanupWorker.schedule(context)
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        async {
            val repo = Repo.get(context)
            oldIds.zip(newIds).forEach { (o, n) ->
                repo.config(o)?.let { repo.saveConfig(n, it); repo.deleteWidget(o) }
            }
        }
    }
}

/** Widget taps: next photo, with optional double-tap detection. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (id == -1) return
        when (intent.action) {
            ACTION_SLIDESHOW_NEXT -> async {
                val cfg = Repo.get(context).config(id) ?: return@async
                val rv = RemoteViews(context.packageName, WidgetUpdater.slideshowLayout(cfg.transition))
                rv.showNext(R.id.slideshow)
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, rv)
            }
            ACTION_TAP -> async {
                val repo = Repo.get(context)
                val cfg = repo.config(id) ?: return@async
                if (cfg.doubleTap == DoubleTapAction.NONE) {
                    Scheduler.runNow(context, id, WidgetUpdater.Action.NEXT)
                    return@async
                }
                val now = SystemClock.elapsedRealtime()
                var isDouble = false
                repo.updateState(id) { st ->
                    isDouble = now - st.lastTap < DOUBLE_TAP_MS
                    st.copy(lastTap = if (isDouble) 0 else now)
                }
                if (isDouble) {
                    when (cfg.doubleTap) {
                        DoubleTapAction.PREVIOUS -> Scheduler.runNow(context, id, WidgetUpdater.Action.PREV)
                        DoubleTapAction.PAUSE -> {
                            repo.updateApp { it.copy(paused = !it.paused) }
                            Scheduler.refreshAll(context)
                        }
                        DoubleTapAction.NONE -> Unit
                    }
                } else {
                    delay(DOUBLE_TAP_MS)
                    if (repo.state(id).lastTap == now) Scheduler.runNow(context, id, WidgetUpdater.Action.NEXT)
                }
            }
        }
    }

    companion object {
        const val ACTION_TAP = "com.widglet.app.TAP"
        const val ACTION_SLIDESHOW_NEXT = "com.widglet.app.SLIDESHOW_NEXT"
        private const val DOUBLE_TAP_MS = 400L
    }
}

/** Called by the launcher after the app asked to pin a widget (Add / Duplicate from inside Widglet). */
class PinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val newId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (newId == -1) return
        val source = intent.getIntExtra(EXTRA_SOURCE, -1)
        async {
            val repo = Repo.get(context)
            if (repo.config(newId) == null) {
                val cfg = if (source != -1) repo.config(source)?.let { it.copy(name = (it.name.ifBlank { "Widget" }) + " copy") } else null
                repo.saveConfig(newId, cfg ?: WidgetConfig())
            }
            repo.config(newId)?.let { Scheduler.sync(context, newId, it) }
            WidgetUpdater.update(context, newId, WidgetUpdater.Action.REFRESH)
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source_id"
    }
}

/** Reboot / app update / language change: re-schedule work and redraw. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        async {
            Scheduler.syncAll(context)
            Scheduler.refreshAll(context)
        }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val action = WidgetUpdater.Action.valueOf(inputData.getString(KEY_ACTION) ?: "NEXT")
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val ids = inputData.getIntArray(KEY_IDS) ?: WidgetUpdater.widgetIds(applicationContext)
        val live = WidgetUpdater.widgetIds(applicationContext).toSet()
        for (id in ids) {
            if (id !in live) continue
            try {
                WidgetUpdater.update(applicationContext, id, action, scheduled)
            } catch (_: Exception) {
                // One broken widget must not stop the others.
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_IDS = "ids"
        const val KEY_ACTION = "action"
        const val KEY_SCHEDULED = "scheduled"
    }
}

/** Picks the right mechanism per timing mode. No exact alarms: WorkManager respects Doze and battery. */
object Scheduler {
    fun periodicName(id: Int) = "update_$id"

    fun sync(context: Context, id: Int, cfg: WidgetConfig) {
        val wm = WorkManager.getInstance(context)
        val minutes = when (cfg.timing) {
            TimingMode.INTERVAL -> cfg.intervalMinutes.coerceAtLeast(15).toLong()
            TimingMode.DAILY -> 24L * 60
            else -> null
        }
        if (minutes == null) {
            wm.cancelUniqueWork(periodicName(id))
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(UpdateWorker.KEY_IDS to intArrayOf(id), UpdateWorker.KEY_ACTION to "NEXT", UpdateWorker.KEY_SCHEDULED to true))
            .addTag("periodic")
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(id), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    suspend fun syncAll(context: Context) {
        val repo = Repo.get(context)
        val live = WidgetUpdater.widgetIds(context).toSet()
        for ((id, cfg) in repo.allConfigs()) {
            if (id in live) sync(context, id, cfg) else {
                // Widget was removed while we weren't running: forget it.
                WorkManager.getInstance(context).cancelUniqueWork(periodicName(id))
                repo.deleteWidget(id)
            }
        }
        CleanupWorker.schedule(context)
    }

    /** Expedited one-off update (tap, tile, shortcut). */
    fun runNow(context: Context, id: Int?, action: WidgetUpdater.Action, scheduled: Boolean = false) {
        val data = if (id != null) {
            workDataOf(UpdateWorker.KEY_IDS to intArrayOf(id), UpdateWorker.KEY_ACTION to action.name, UpdateWorker.KEY_SCHEDULED to scheduled)
        } else {
            workDataOf(UpdateWorker.KEY_ACTION to action.name, UpdateWorker.KEY_SCHEDULED to scheduled)
        }
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("now_${id ?: "all"}", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun refreshAll(context: Context) = runNow(context, null, WidgetUpdater.Action.REFRESH)
}

/**
 * "On unlock" / "On screen on" modes. Android only delivers these broadcasts to receivers registered at
 * runtime, so this works while Widglet's process is alive (best effort, labelled as such in the UI).
 */
object ScreenEvents {
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val mode = if (intent.action == Intent.ACTION_USER_PRESENT) TimingMode.UNLOCK else TimingMode.SCREEN_ON
                async {
                    val repo = Repo.get(ctx)
                    repo.allConfigs().filter { it.value.timing == mode }.keys.forEach { id ->
                        if (System.currentTimeMillis() - repo.state(id).lastUpdate > 20_000) {
                            Scheduler.runNow(ctx, id, WidgetUpdater.Action.NEXT, scheduled = true)
                        }
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}
