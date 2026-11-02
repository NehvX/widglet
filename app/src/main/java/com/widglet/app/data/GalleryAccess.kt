/*
 * GalleryAccess — THE ONLY way Widglet touches the user's photos.
 *
 * Widglet is strictly READ-ONLY with respect to the user's gallery. This class exposes nothing but
 * read operations (query, decode, thumbnail, existence check). It never deletes, inserts, updates,
 * renames, moves, re-saves or edits any image, album or metadata in MediaStore or shared storage,
 * and it only ever opens sources for reading. Everything Widglet produces (cutouts, caches) lives in
 * the app's private filesDir/cacheDir.
 *
 * Do not add write methods here. The Gradle task `checkGalleryReadOnly` (and GalleryReadOnlyTest)
 * fail the build if MediaStore/ContentResolver write or delete APIs appear anywhere in the app.
 */
package com.widglet.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AccessLevel { FULL, PARTIAL, NONE }

class GalleryAccess private constructor(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun accessLevel(): AccessLevel {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> AccessLevel.FULL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> AccessLevel.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessLevel.PARTIAL
            else -> AccessLevel.NONE
        }
    }

    /** All albums (MediaStore buckets) with name, photo count and a cover. */
    fun albums(): List<Album> {
        if (accessLevel() == AccessLevel.NONE) return emptyList()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        data class Acc(val name: String, var count: Int, val cover: Long)
        val map = LinkedHashMap<Long, Acc>()
        safeQuery(IMAGES, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bC = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nC = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val b = c.getLong(bC)
                val acc = map[b]
                if (acc == null) map[b] = Acc(c.getString(nC) ?: "Unnamed", 1, c.getLong(idC)) else acc.count++
            }
        }
        return map.map { (id, a) -> Album(id, a.name, a.count, ContentUris.withAppendedId(IMAGES, a.cover).toString()) }
            .sortedByDescending { it.count }
    }

    /** Photos in the given buckets. Missing buckets simply return nothing. */
    fun photosInBuckets(bucketIds: Collection<Long>): List<Photo> {
        if (bucketIds.isEmpty() || accessLevel() == AccessLevel.NONE) return emptyList()
        val sel = "${MediaStore.Images.Media.BUCKET_ID} IN (${bucketIds.joinToString(",") { "?" }})"
        val args = bucketIds.map { it.toString() }.toTypedArray()
        val out = ArrayList<Photo>()
        safeQuery(IMAGES, PHOTO_PROJECTION, sel, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext()) readPhoto(c, null)?.let(out::add)
        }
        return out
    }

    /** Every photo this app can see (used to list what partial access exposes). */
    fun allVisiblePhotos(limit: Int = 500): List<Photo> {
        if (accessLevel() == AccessLevel.NONE) return emptyList()
        val out = ArrayList<Photo>()
        safeQuery(IMAGES, PHOTO_PROJECTION, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) readPhoto(c, null)?.let(out::add)
        }
        return out
    }

    /** Metadata for a single picked Uri, or null if it no longer exists / is unreadable. */
    fun photoMeta(uri: String): Photo? {
        val u = Uri.parse(uri)
        val proj = arrayOf(
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DISPLAY_NAME,
        )
        return try {
            resolver.query(u, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) 0L else c.getLong(it) } ?: 0L
                fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
                Photo(
                    uri = uri, dateTaken = long(MediaStore.MediaColumns.DATE_TAKEN),
                    width = long(MediaStore.MediaColumns.WIDTH).toInt(), height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
                    size = long(MediaStore.MediaColumns.SIZE), displayName = str(MediaStore.MediaColumns.DISPLAY_NAME),
                )
            } ?: Photo(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            // Some providers don't support every column; the photo may still be decodable.
            if (exists(uri)) Photo(uri) else null
        }
    }

    /** True if the Uri can still be opened for reading. */
    fun exists(uri: String): Boolean = try {
        resolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Decode an image downsampled so that it covers [reqW]x[reqH] (never larger than the source).
     * EXIF orientation is applied by ImageDecoder in memory; the source file is opened read-only and never written.
     */
    fun decode(uri: String, reqW: Int, reqH: Int, fitInside: Boolean = false): Bitmap? = try {
        val src = ImageDecoder.createSource(resolver, Uri.parse(uri))
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { true } // show what we can of truncated files
            val w = info.size.width.coerceAtLeast(1)
            val h = info.size.height.coerceAtLeast(1)
            val s = if (fitInside) min(reqW.toFloat() / w, reqH.toFloat() / h) else max(reqW.toFloat() / w, reqH.toFloat() / h)
            if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null // corrupt, deleted, permission revoked...
    }

    /** Small thumbnail via the system thumbnail cache (read-only). */
    fun thumbnail(uri: String, px: Int): Bitmap? = try {
        resolver.loadThumbnail(Uri.parse(uri), Size(px, px), null)
    } catch (_: Exception) {
        decode(uri, px, px)
    }

    /** Keep read access to Photo Picker results across reboots (read flag only). */
    fun persistReadGrant(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable on this device; the grant lasts until reboot.
        }
    }

    private fun safeQuery(uri: Uri, proj: Array<String>, sel: String?, args: Array<String>?, sort: String?): Cursor? = try {
        resolver.query(uri, proj, sel, args, sort)
    } catch (_: Exception) {
        null
    }

    private fun readPhoto(c: Cursor, bucketOverride: String?): Photo? {
        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        fun idx(col: String) = c.getColumnIndex(col)
        val taken = idx(MediaStore.Images.Media.DATE_TAKEN).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else 0L }
        val added = idx(MediaStore.Images.Media.DATE_ADDED).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) * 1000 else 0L }
        return Photo(
            uri = ContentUris.withAppendedId(IMAGES, id).toString(),
            dateTaken = if (taken > 0) taken else added,
            bucketId = idx(MediaStore.Images.Media.BUCKET_ID).let { if (it >= 0) c.getLong(it) else null },
            bucketName = bucketOverride ?: idx(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).let { if (it >= 0) c.getString(it) else null },
            width = idx(MediaStore.Images.Media.WIDTH).let { if (it >= 0) c.getInt(it) else 0 },
            height = idx(MediaStore.Images.Media.HEIGHT).let { if (it >= 0) c.getInt(it) else 0 },
            size = idx(MediaStore.Images.Media.SIZE).let { if (it >= 0) c.getLong(it) else 0L },
            displayName = idx(MediaStore.Images.Media.DISPLAY_NAME).let { if (it >= 0) c.getString(it) else null },
            isFavorite = idx(MediaStore.MediaColumns.IS_FAVORITE).let { it >= 0 && c.getInt(it) == 1 },
            relativePath = idx(MediaStore.MediaColumns.RELATIVE_PATH).let { if (it >= 0) c.getString(it) else null },
        )
    }

    companion object {
        private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.RELATIVE_PATH,
        )

        @Volatile private var instance: GalleryAccess? = null
        fun get(context: Context): GalleryAccess =
            instance ?: synchronized(this) { instance ?: GalleryAccess(context.applicationContext).also { instance = it } }

        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
