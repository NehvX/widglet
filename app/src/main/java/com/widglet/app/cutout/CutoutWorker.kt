package com.widglet.app.cutout

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.widglet.app.data.AccessLevel
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.PhotoSelector
import com.widglet.app.data.Repo
import com.widglet.app.data.WidgetConfig
import com.widglet.app.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/** Generates cutouts in the background with progress. Results go to private storage only. */
class CutoutWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_ID, -1)
        val repo = Repo.get(applicationContext)
        val cfg = repo.config(id) ?: return Result.success()
        if (!cfg.cutout) return Result.success()
        val store = CutoutStore.get(applicationContext)
        val explicit = inputData.getStringArray(KEY_URIS)?.toList()
        val photos = if (explicit != null) {
            explicit.map { com.widglet.app.data.Photo(it) }
        } else {
            val seed = repo.state(id).shuffleSeed
            PhotoSelector(applicationContext).resolve(cfg, seed).photos
        }
        val todo = photos.filter { store.status(it.uri, cfg.quality) == CutoutStore.Status.MISSING }
        var made = 0
        var none = 0
        todo.forEachIndexed { i, p ->
            if (isStopped) return Result.retry()
            setProgress(workDataOf(P_DONE to i, P_TOTAL to todo.size, P_MADE to made, P_NONE to none))
            when (val r = Segmenter.make(applicationContext, p.uri, cfg.quality, p.size)) {
                is Segmenter.MakeResult.Made -> {
                    made++
                    repo.updateStats { it.copy(cutoutsMade = it.cutoutsMade + 1, cutoutBytes = it.cutoutBytes + r.bytes, sourceBytes = it.sourceBytes + r.sourceBytes) }
                }
                Segmenter.MakeResult.NoSubject -> {
                    none++
                    repo.updateStats { it.copy(noSubject = it.noSubject + 1) }
                }
                Segmenter.MakeResult.ModelUnavailable -> {
                    setProgress(workDataOf(P_MODEL_MISSING to true, P_TOTAL to todo.size))
                    return Result.retry() // Play services is still downloading the model
                }
                Segmenter.MakeResult.Failed -> Unit // corrupt/unreadable photo: skip quietly
            }
        }
        setProgress(workDataOf(P_DONE to todo.size, P_TOTAL to todo.size, P_MADE to made, P_NONE to none))
        if (made > 0) WidgetUpdater.refresh(applicationContext, id)
        return Result.success(workDataOf(P_MADE to made, P_NONE to none))
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_URIS = "uris"
        const val P_DONE = "done"
        const val P_TOTAL = "total"
        const val P_MADE = "made"
        const val P_NONE = "none"
        const val P_MODEL_MISSING = "model_missing"

        fun uniqueName(id: Int) = "cutouts_$id"

        private fun constraints(cfg: WidgetConfig) = Constraints.Builder()
            .setRequiresCharging(cfg.processOnlyCharging)
            .setRequiredNetworkType(if (cfg.processOnlyWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresStorageNotLow(true)
            .build()

        /** Process every photo of the widget (batch mode). */
        fun enqueueAll(context: Context, id: Int, cfg: WidgetConfig) {
            val req = OneTimeWorkRequestBuilder<CutoutWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(constraints(cfg))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("cutouts")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, req)
        }

        /** Lazy mode: only the next few photos. Keeps a running batch if one exists. */
        fun enqueueUpcoming(context: Context, id: Int, cfg: WidgetConfig, uris: List<String>) {
            if (uris.isEmpty()) return
            val req = OneTimeWorkRequestBuilder<CutoutWorker>()
                .setInputData(workDataOf(KEY_ID to id, KEY_URIS to uris.toTypedArray()))
                .setConstraints(constraints(cfg))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("cutouts")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** Smart cache cleanup: removes cutouts no widget uses any more. Never touches anything outside our own folder. */
class CleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        cleanUnused(applicationContext)
        return Result.success()
    }

    companion object {
        /** Returns the number of private files removed, or -1 if skipped for safety. */
        suspend fun cleanUnused(context: Context): Int {
            // If we can't currently read the gallery we can't tell what's still used: do nothing.
            if (GalleryAccess.get(context).accessLevel() == AccessLevel.NONE) return -1
            val repo = Repo.get(context)
            val store = CutoutStore.get(context)
            val keep = HashSet<String>()
            for ((id, cfg) in repo.allConfigs()) {
                if (!cfg.cutout) continue
                val photos = PhotoSelector(context).resolve(cfg.copy(noSubject = com.widglet.app.data.NoSubjectAction.SHOW_FULL), repo.state(id).shuffleSeed).photos
                if (photos.isEmpty() && cfg.hasSources) return -1 // looks like a transient failure; be conservative
                photos.forEach { keep += store.names(it.uri, cfg.quality) }
            }
            return store.deleteAllExcept(keep)
        }

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("cleanup", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "cleanup_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CleanupWorker>().build(),
            )
        }
    }
}
