package com.widglet.app.cutout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.Quality
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/** On-device subject segmentation (ML Kit, model delivered by Google Play services; no network from Widglet). */
object Segmenter {
    sealed interface Outcome {
        data class Mask(val bitmap: Bitmap, val coverage: Float) : Outcome // ARGB mask, alpha = subject
        data object NoSubject : Outcome
        data object ModelUnavailable : Outcome
        data object Failed : Outcome
    }

    private const val SEG_MAX = 1024
    private const val MIN_COVERAGE = 0.015f

    private val client by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    suspend fun mask(src: Bitmap): Outcome {
        val input = if (maxOf(src.width, src.height) > SEG_MAX) {
            val s = SEG_MAX.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
        } else src
        return try {
            val result = client.process(InputImage.fromBitmap(input, 0)).await()
            val buf = result.foregroundConfidenceMask ?: return Outcome.NoSubject
            buf.rewind()
            val w = input.width
            val h = input.height
            val px = IntArray(w * h)
            var fg = 0
            for (i in 0 until w * h) {
                val c = if (buf.hasRemaining()) buf.get() else 0f
                if (c > 0.5f) fg++
                // Soft edge: ramp confidence 0.25..0.75 to alpha 0..255
                val a = (((c - 0.25f) / 0.5f).coerceIn(0f, 1f) * 255).toInt()
                px[i] = a shl 24
            }
            val coverage = fg.toFloat() / (w * h)
            if (coverage < MIN_COVERAGE) Outcome.NoSubject
            else Outcome.Mask(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), coverage)
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) Outcome.ModelUnavailable else Outcome.Failed
        } catch (_: Throwable) {
            Outcome.Failed
        } finally {
            if (input !== src) input.recycle()
        }
    }

    /** Applies [mask] to [photo] (scaled to the photo's size) and returns a transparent cutout. */
    fun applyMask(photo: Bitmap, mask: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(photo, 0f, 0f, null)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        c.drawBitmap(mask, null, Rect(0, 0, photo.width, photo.height), p)
        return out
    }

    fun scaleToMax(b: Bitmap, maxPx: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxPx) return b
        val s = maxPx.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    sealed interface MakeResult {
        data class Made(val bytes: Long, val sourceBytes: Long) : MakeResult
        data object NoSubject : MakeResult
        data object ModelUnavailable : MakeResult
        data object Failed : MakeResult
    }

    /** Generate and store one cutout at [q]. Reads the source photo read-only; writes only to private storage. */
    suspend fun make(context: Context, uri: String, q: Quality, sourceBytes: Long = 0): MakeResult {
        val store = CutoutStore.get(context)
        val photo = GalleryAccess.get(context).decode(uri, q.maxPx, q.maxPx, fitInside = true) ?: return MakeResult.Failed
        try {
            return when (val m = mask(photo)) {
                is Outcome.Mask -> {
                    val cut = applyMask(photo, m.bitmap)
                    m.bitmap.recycle()
                    val bytes = store.save(uri, q, cut)
                    cut.recycle()
                    MakeResult.Made(bytes, sourceBytes)
                }
                Outcome.NoSubject -> { store.markNoSubject(uri, q); MakeResult.NoSubject }
                Outcome.ModelUnavailable -> MakeResult.ModelUnavailable
                Outcome.Failed -> MakeResult.Failed
            }
        } finally {
            photo.recycle()
        }
    }

    data class Sample(val avgBytes: Map<Quality, Long>, val noSubjectRate: Float, val sampled: Int, val modelMissing: Boolean)

    /**
     * Estimate storage from REAL cutouts: segment a few sample photos once, then encode the cutout at every
     * quality level in memory and measure the bytes. Nothing is written to disk.
     */
    suspend fun sample(context: Context, uris: List<String>, n: Int = 3): Sample {
        val gallery = GalleryAccess.get(context)
        val totals = Quality.entries.associateWith { 0L }.toMutableMap()
        var made = 0
        var none = 0
        var missing = false
        for (uri in uris.shuffled().take(n * 2)) {
            if (made + none >= n) break
            val photo = gallery.decode(uri, Quality.MAX.maxPx, Quality.MAX.maxPx, fitInside = true) ?: continue
            when (val m = mask(photo)) {
                is Outcome.Mask -> {
                    val full = applyMask(photo, m.bitmap)
                    for (q in Quality.entries) {
                        val scaled = scaleToMax(full, q.maxPx)
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, q.webpQuality, bos)
                        totals[q] = totals.getValue(q) + bos.size()
                        if (scaled !== full) scaled.recycle()
                    }
                    full.recycle(); m.bitmap.recycle()
                    made++
                }
                Outcome.NoSubject -> none++
                Outcome.ModelUnavailable -> { missing = true; photo.recycle(); break }
                Outcome.Failed -> Unit
            }
            photo.recycle()
        }
        val avg = totals.mapValues { (_, v) -> if (made > 0) v / made else 0L }
        val rate = if (made + none > 0) none.toFloat() / (made + none) else 0f
        return Sample(avg, rate, made + none, missing)
    }
}

object StorageMath {
    /** Estimated bytes for [count] photos given a per-cutout average and the share with no subject. */
    fun estimate(count: Int, avgBytes: Long, noSubjectRate: Float): Long =
        (count * (1f - noSubjectRate.coerceIn(0f, 1f)) * avgBytes).toLong()

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    const val WARN_BYTES = 200L shl 20
}
