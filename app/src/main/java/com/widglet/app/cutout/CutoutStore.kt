package com.widglet.app.cutout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.widglet.app.data.Quality
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Cutout cache in the app's PRIVATE storage (filesDir/cutouts). Nothing here ever touches the gallery:
 * every file read or deleted by this class is one Widglet created itself.
 */
class CutoutStore private constructor(context: Context) {
    enum class Status { READY, NO_SUBJECT, MISSING }

    val dir: File = File(context.filesDir, "cutouts").apply { mkdirs() }

    fun key(uri: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun file(uri: String, q: Quality) = File(dir, "${key(uri)}_${q.name}.webp")
    private fun noneMarker(uri: String, q: Quality) = File(dir, "${key(uri)}_${q.name}.none")

    fun status(uri: String, q: Quality): Status = when {
        file(uri, q).exists() -> Status.READY
        noneMarker(uri, q).exists() -> Status.NO_SUBJECT
        else -> Status.MISSING
    }

    fun load(uri: String, q: Quality): Bitmap? = try {
        BitmapFactory.decodeFile(file(uri, q).path)
    } catch (_: Throwable) {
        null
    }

    /** Writes atomically (temp file + rename) so a killed worker never leaves a broken cutout. */
    fun save(uri: String, q: Quality, bmp: Bitmap): Long {
        val target = file(uri, q)
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q.webpQuality, it) }
        tmp.renameTo(target)
        noneMarker(uri, q).delete()
        return target.length()
    }

    fun markNoSubject(uri: String, q: Quality) {
        noneMarker(uri, q).writeText("")
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun bytesFor(uris: Collection<String>, q: Quality): Long = uris.sumOf { file(it, q).let { f -> if (f.exists()) f.length() else 0L } }

    /** Delete our own cutouts that no widget references any more. [keep] = set of file names to keep. */
    fun deleteAllExcept(keep: Set<String>): Int {
        var n = 0
        dir.listFiles()?.forEach { if (it.name !in keep && it.delete()) n++ }
        return n
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun names(uri: String, q: Quality) = listOf(file(uri, q).name, noneMarker(uri, q).name)

    companion object {
        @Volatile private var instance: CutoutStore? = null
        fun get(context: Context): CutoutStore =
            instance ?: synchronized(this) { instance ?: CutoutStore(context.applicationContext).also { instance = it } }
    }
}
