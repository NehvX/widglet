package com.widglet.app.data

import android.content.Context
import com.widglet.app.cutout.CutoutStore
import java.util.Calendar
import kotlin.random.Random

/** Pure ordering helpers (unit tested). */
object PhotoOrdering {
    fun ordered(photos: List<Photo>, order: Order, seed: Long): List<Photo> = when (order) {
        Order.NEWEST -> photos.sortedByDescending { it.dateTaken }
        Order.OLDEST -> photos.sortedBy { it.dateTaken }
        Order.SEQUENTIAL -> photos.sortedWith(compareBy<Photo>({ it.bucketName ?: "" }, { it.displayName ?: it.uri }))
        Order.SHUFFLE -> photos.sortedBy { it.uri }.shuffled(Random(seed))
    }

    /** Index of the photo after (or before, if [step] is -1) [current]; wraps around. */
    fun step(list: List<String>, current: String?, step: Int = 1): Int {
        if (list.isEmpty()) return -1
        val i = list.indexOf(current)
        if (i < 0) return if (step >= 0) 0 else list.lastIndex
        return Math.floorMod(i + step, list.size)
    }

    fun slotFor(cal: Calendar): TimeSlot = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> TimeSlot.MORNING
        in 12..16 -> TimeSlot.AFTERNOON
        in 17..20 -> TimeSlot.EVENING
        else -> TimeSlot.NIGHT
    }

    fun isWeekend(cal: Calendar): Boolean =
        cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

    /** Albums to use right now: a matching time-of-day rule wins, then weekday/weekend, else the defaults. */
    fun activeAlbums(cfg: WidgetConfig, cal: Calendar): List<AlbumRef> {
        if (!cfg.timeRulesEnabled) return cfg.albums
        val slot = slotFor(cal)
        cfg.timeRules.firstOrNull { it.slot == slot && it.albums.isNotEmpty() }?.let { return it.albums }
        val dayKind = if (isWeekend(cal)) TimeSlot.WEEKEND else TimeSlot.WEEKDAY
        cfg.timeRules.firstOrNull { it.slot == dayKind && it.albums.isNotEmpty() }?.let { return it.albums }
        return cfg.albums
    }
}

/** Resolves a widget's config into the concrete, filtered, ordered list of photos it should cycle through. */
class PhotoSelector(private val context: Context) {
    private val gallery = GalleryAccess.get(context)

    data class Result(val photos: List<Photo>, val onThisDayFallback: Boolean = false)

    fun resolve(cfg: WidgetConfig, seed: Long, now: Calendar = Calendar.getInstance()): Result {
        val albums = PhotoOrdering.activeAlbums(cfg, now)
        val fromAlbums = gallery.photosInBuckets(albums.map { it.id })
        // Picked photos that have vanished from the gallery are skipped quietly.
        val picked = cfg.pickedUris.mapNotNull { gallery.photoMeta(it) }
        var list = (fromAlbums + picked).distinctBy { it.uri }.filterNot { it.uri in cfg.hidden }

        if (cfg.favouritesOnly) list = list.filter { it.isFavorite }
        if (cfg.skipScreenshots) list = list.filterNot { isScreenshot(it) }
        if (cfg.skipSmall) list = list.filter { it.width == 0 || minOf(it.width, it.height) >= cfg.minSidePx }
        cfg.dateFrom?.let { from -> list = list.filter { it.dateTaken == 0L || it.dateTaken >= from } }
        cfg.dateTo?.let { to -> list = list.filter { it.dateTaken == 0L || it.dateTaken <= to + DAY_MS } }
        if (cfg.skipDuplicates) {
            list = list.distinctBy { p -> if (p.size > 0 && p.width > 0) "${p.size}x${p.width}x${p.height}" else p.uri }
        }
        if (cfg.cutout && cfg.noSubject == NoSubjectAction.SKIP) {
            val store = CutoutStore.get(context)
            list = list.filterNot { store.status(it.uri, cfg.quality) == CutoutStore.Status.NO_SUBJECT }
        }

        var fallback = false
        if (cfg.onThisDay) {
            val month = now.get(Calendar.MONTH)
            val day = now.get(Calendar.DAY_OF_MONTH)
            val year = now.get(Calendar.YEAR)
            val c = Calendar.getInstance()
            val matches = list.filter {
                if (it.dateTaken <= 0) return@filter false
                c.timeInMillis = it.dateTaken
                c.get(Calendar.MONTH) == month && c.get(Calendar.DAY_OF_MONTH) == day && c.get(Calendar.YEAR) < year
            }
            if (matches.isNotEmpty()) list = matches else fallback = true
        }

        var ordered = PhotoOrdering.ordered(list, cfg.order, seed)
        if (cfg.cutout && cfg.preferSubjects) {
            val store = CutoutStore.get(context)
            val (good, rest) = ordered.partition { store.status(it.uri, cfg.quality) == CutoutStore.Status.READY }
            ordered = good + rest
        }
        return Result(ordered, fallback)
    }

    private fun isScreenshot(p: Photo): Boolean {
        val path = (p.relativePath ?: "") + "/" + (p.bucketName ?: "")
        return path.contains("screenshot", ignoreCase = true) || (p.displayName ?: "").startsWith("Screenshot", ignoreCase = true)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
