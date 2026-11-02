package com.widglet.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.widglet.app.cutout.CutoutStore
import com.widglet.app.data.CutoutBg
import com.widglet.app.data.Fit
import com.widglet.app.data.GalleryAccess
import com.widglet.app.data.NoSubjectAction
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoFilter
import com.widglet.app.data.Shape
import com.widglet.app.data.TextStyle
import com.widglet.app.data.WidgetConfig
import com.widglet.app.data.WidgetLayout
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Draws a complete widget frame into bitmaps (everything except the live clock is baked in, so it looks the
 * same on every launcher). [Frame.front] is only set when the subject must sit above the clock (depth effect).
 * All input photos are read-only; output bitmaps live only in memory.
 */
class Renderer(private val context: Context) {
    data class Frame(val back: Bitmap, val front: Bitmap?, val photo: Photo?, val description: String)

    private val gallery = GalleryAccess.get(context)
    private val cutouts = CutoutStore.get(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = v * density
    private fun dp(v: Float) = v * density

    /**
     * [photos]: index 0 is the current photo. For STACK, 1..2 are the previously shown ones; for COLLAGE the
     * following ones fill the other cells.
     */
    fun render(cfg: WidgetConfig, photos: List<Photo>, w: Int, h: Int): Frame {
        val back = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(back)
        val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = dp(cfg.cornerRadius)

        // Widget background (behind everything, follows the corner radius).
        if (Color.alpha(cfg.bgColor) > 0) {
            c.drawPath(shapePath(if (cfg.shape in FRAME_SHAPES) Shape.ROUNDED else cfg.shape, outer, radius), fill(cfg.bgColor))
        }
        val inner = RectF(outer).apply { inset(dp(cfg.padding), dp(cfg.padding)) }
        if (inner.width() < 4 || inner.height() < 4) return Frame(back, null, photos.firstOrNull(), "")

        var front: Bitmap? = null
        when (cfg.layout) {
            WidgetLayout.SINGLE -> front = drawSingle(c, cfg, photos.firstOrNull(), inner, radius, w, h)
            WidgetLayout.STACK -> drawStack(c, cfg, photos.take(3), inner)
            WidgetLayout.COLLAGE -> drawCollage(c, cfg, photos.take(cfg.collageCount.coerceIn(2, 4)), inner, radius)
        }
        val p = photos.firstOrNull()
        return Frame(back, front, p, describe(p))
    }

    // ------------------------------------------------------------------ single
    private fun drawSingle(c: Canvas, cfg: WidgetConfig, photo: Photo?, inner: RectF, radius: Float, w: Int, h: Int): Bitmap? {
        val window: RectF
        val windowPath: Path
        var textOnFrame = false
        when (cfg.shape) {
            Shape.POLAROID -> {
                window = drawPolaroidFrame(c, inner)
                windowPath = Path().apply { addRect(window, Path.Direction.CW) }
                textOnFrame = true
            }
            Shape.FILM -> {
                window = drawFilmFrame(c, inner)
                windowPath = Path().apply { addRoundRect(window, dp(3), dp(3), Path.Direction.CW) }
            }
            else -> {
                windowPath = shapePath(cfg.shape, inner, radius)
                window = RectF().also { windowPath.computeBounds(it, true) }
            }
        }
        if (photo == null) {
            c.drawPath(windowPath, fill(0x33FFFFFF))
            return null
        }

        var front: Bitmap? = null
        val cut = if (cfg.cutout) cutouts.load(photo.uri, cfg.quality) else null
        val orig = gallery.decode(photo.uri, window.width().toInt().coerceAtLeast(1), window.height().toInt().coerceAtLeast(1))

        c.save()
        c.clipPath(windowPath)
        if (cut != null && orig != null) {
            val dst = mapRect(orig.width, orig.height, window, cfg.fit, focalPoint(cut))
            when (cfg.cutoutBg) {
                CutoutBg.TRANSPARENT -> Unit
                CutoutBg.SOLID -> c.drawRect(window, fill(cfg.bgColor2))
                CutoutBg.GRADIENT -> c.drawRect(window, Paint().apply {
                    shader = LinearGradient(window.left, window.top, window.right, window.bottom, cfg.bgColor2, cfg.accentColor, Shader.TileMode.CLAMP)
                })
                CutoutBg.BLUR -> fastBlur(orig).let { c.drawBitmap(it, null, dst, FILTER); it.recycle() }
                CutoutBg.ORIGINAL -> drawPhoto(c, orig, dst, window, cfg)
            }
            c.restore()
            // Subject layer: same mapping as the photo so it lines up exactly; may pop out of the shape.
            val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(layer).apply {
                clipRect(if (cfg.shape in FRAME_SHAPES) window else RectF(0f, 0f, w.toFloat(), h.toFloat()))
                drawBitmap(cut, null, dst, FILTER)
            }
            var subject = if (cfg.filter == PhotoFilter.DOT_MATRIX) dotMatrix(layer, dotCell(window)).also { layer.recycle() } else layer
            if (cfg.filter == PhotoFilter.MONO) subject = mono(subject)
            subject = sticker(subject, cfg)
            if (cfg.clock) front = subject else { c.drawBitmap(subject, 0f, 0f, null); subject.recycle() }
            c.save(); c.clipPath(windowPath)
        } else if (orig != null) {
            // Full photo (or cutout mode with no subject / cutout not generated yet). A cutout from an
            // earlier cutout session still helps smart cropping if one exists.
            val focal = if (cfg.fit == Fit.SMART_FILL) cutouts.load(photo.uri, cfg.quality)?.let { b -> focalPoint(b).also { b.recycle() } } else null
            drawPhoto(c, orig, mapRect(orig.width, orig.height, window, cfg.fit, focal), window, cfg)
        } else {
            c.drawPath(windowPath, fill(0x33FFFFFF))
        }
        if (!textOnFrame) drawOverlayText(c, cfg, photo, window, onLight = false)
        c.restore()
        if (textOnFrame) drawOverlayText(c, cfg, photo, RectF(window.left, window.bottom, window.right, inner.bottom), onLight = true)
        if (cfg.border) c.drawPath(if (cfg.shape in FRAME_SHAPES) shapePath(Shape.ROUNDED, inner, dp(4)) else windowPath, stroke(cfg.borderColor, dp(cfg.borderWidth)))
        cut?.recycle(); orig?.recycle()
        return front
    }

    private fun drawPhoto(c: Canvas, src: Bitmap, dst: RectF, window: RectF, cfg: WidgetConfig) {
        when (cfg.filter) {
            PhotoFilter.NONE -> c.drawBitmap(src, null, dst, FILTER)
            PhotoFilter.MONO -> c.drawBitmap(src, null, dst, monoPaint())
            PhotoFilter.DOT_MATRIX -> {
                val tmp = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
                Canvas(tmp).apply { clipRect(window); drawBitmap(src, null, dst, FILTER) }
                val dots = dotMatrix(tmp, dotCell(window))
                tmp.recycle()
                c.drawRect(window, fill(Color.BLACK))
                c.drawBitmap(dots, 0f, 0f, null)
                dots.recycle()
            }
        }
    }

    // ------------------------------------------------------------------ stack
    private fun drawStack(c: Canvas, cfg: WidgetConfig, photos: List<Photo>, area: RectF) {
        val cw = area.width() * 0.76f
        val ch = area.height() * 0.76f
        val layers = photos.reversed()
        layers.forEachIndexed { i, photo ->
            val isTop = i == layers.lastIndex
            val angle = if (isTop) ((photo.uri.hashCode() % 5) - 2).toFloat() else ((photo.uri.hashCode() % 17) - 8).toFloat()
            val dx = if (isTop) 0f else (if (i % 2 == 0) -1 else 1) * area.width() * 0.05f
            val rect = RectF(area.centerX() - cw / 2 + dx, area.centerY() - ch / 2, area.centerX() + cw / 2 + dx, area.centerY() + ch / 2)
            c.save()
            c.rotate(angle, rect.centerX(), rect.centerY())
            drawPrint(c, cfg, photo, rect, withText = isTop)
            c.restore()
        }
    }

    private fun drawPrint(c: Canvas, cfg: WidgetConfig, photo: Photo, rect: RectF, withText: Boolean) {
        val cut = if (cfg.cutout) cutouts.load(photo.uri, cfg.quality) else null
        if (cut != null) {
            // Cutout prints become stickers.
            val layer = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
            Canvas(layer).drawBitmap(cut, null, mapRect(cut.width, cut.height, rect, Fit.FIT, null), FILTER)
            val st = sticker(layer, cfg.copy(outline = true))
            c.drawBitmap(st, 0f, 0f, null)
            st.recycle(); cut.recycle()
            return
        }
        // Soft shadow under the print.
        c.drawRoundRect(RectF(rect).apply { offset(0f, dp(3)) }, dp(4), dp(4), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000; maskFilter = BlurMaskFilter(dp(6), BlurMaskFilter.Blur.NORMAL)
        })
        val window = if (cfg.shape == Shape.POLAROID) drawPolaroidFrame(c, rect) else {
            c.drawRoundRect(rect, dp(4), dp(4), fill(0xFFFAFAFA.toInt()))
            RectF(rect).apply { inset(rect.width() * 0.04f, rect.width() * 0.04f) }
        }
        gallery.decode(photo.uri, window.width().toInt(), window.height().toInt())?.let { b ->
            c.save(); c.clipRect(window)
            drawPhoto(c, b, mapRect(b.width, b.height, window, Fit.CENTER_CROP, null), window, cfg)
            c.restore(); b.recycle()
        }
        if (withText && cfg.shape == Shape.POLAROID) drawOverlayText(c, cfg, photo, RectF(window.left, window.bottom, window.right, rect.bottom), onLight = true)
    }

    // ------------------------------------------------------------------ collage
    private fun drawCollage(c: Canvas, cfg: WidgetConfig, photos: List<Photo>, area: RectF, radius: Float) {
        val gap = min(area.width(), area.height()) * 0.03f
        val cells = collageCells(photos.size, area, gap)
        val r = min(radius, min(area.width(), area.height()) * 0.12f)
        cells.zip(photos).forEach { (cell, photo) ->
            val path = Path().apply { addRoundRect(cell, r, r, Path.Direction.CW) }
            c.save(); c.clipPath(path)
            val cut = if (cfg.cutout) cutouts.load(photo.uri, cfg.quality) else null
            if (cut != null) {
                c.drawRect(cell, fill(if (Color.alpha(cfg.bgColor2) > 0) cfg.bgColor2 else 0xFF303030.toInt()))
                c.drawBitmap(cut, null, mapRect(cut.width, cut.height, cell, Fit.FIT, null), FILTER)
                cut.recycle()
            } else {
                gallery.decode(photo.uri, cell.width().toInt(), cell.height().toInt())?.let { b ->
                    drawPhoto(c, b, mapRect(b.width, b.height, cell, Fit.CENTER_CROP, null), cell, cfg); b.recycle()
                } ?: c.drawRect(cell, fill(0x33FFFFFF))
            }
            c.restore()
            if (cfg.border) c.drawPath(path, stroke(cfg.borderColor, dp(cfg.borderWidth)))
        }
    }

    // ------------------------------------------------------------------ frames
    private fun drawPolaroidFrame(c: Canvas, r: RectF): RectF {
        c.drawRoundRect(r, dp(4), dp(4), fill(0xFFFAFAF7.toInt()))
        val m = min(r.width(), r.height()) * 0.06f
        return RectF(r.left + m, r.top + m, r.right - m, r.bottom - max(m * 3.2f, r.height() * 0.18f))
    }

    private fun drawFilmFrame(c: Canvas, r: RectF): RectF {
        c.drawRoundRect(r, dp(4), dp(4), fill(0xFF141414.toInt()))
        val strip = r.height() * 0.13f
        val holeH = strip * 0.45f
        val holeW = holeH * 1.3f
        val step = holeW * 2f
        val hole = fill(0xFFE8E2D0.toInt())
        var x = r.left + step / 2
        while (x + holeW < r.right) {
            c.drawRoundRect(RectF(x, r.top + (strip - holeH) / 2, x + holeW, r.top + (strip + holeH) / 2), holeH * 0.2f, holeH * 0.2f, hole)
            c.drawRoundRect(RectF(x, r.bottom - (strip + holeH) / 2, x + holeW, r.bottom - (strip - holeH) / 2), holeH * 0.2f, holeH * 0.2f, hole)
            x += step
        }
        return RectF(r.left + r.width() * 0.03f, r.top + strip, r.right - r.width() * 0.03f, r.bottom - strip)
    }

    // ------------------------------------------------------------------ text
    private fun drawOverlayText(c: Canvas, cfg: WidgetConfig, photo: Photo, area: RectF, onLight: Boolean) {
        val parts = buildList {
            if (cfg.showDate && photo.dateTaken > 0) add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(photo.dateTaken)))
            if (cfg.showAlbum && !photo.bucketName.isNullOrBlank()) add(photo.bucketName)
        }
        if (parts.isEmpty()) return
        val text = parts.joinToString(" · ")
        val size = (min(area.width(), if (onLight) area.height() else area.height() * 0.5f) * 0.11f).coerceIn(dp(9), dp(16))
        val color = if (onLight) 0xFF333333.toInt() else Color.WHITE
        val pad = size * 0.8f
        if (!onLight) {
            // Scrim for legibility.
            val top = area.bottom - size * 3.2f
            c.drawRect(area.left, top, area.right, area.bottom, Paint().apply {
                shader = LinearGradient(0f, top, 0f, area.bottom, 0x00000000, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            })
        }
        val baseline = if (onLight) area.centerY() + size * 0.35f else area.bottom - pad
        var x = area.left + pad
        if (cfg.textStyle == TextStyle.DOT_MATRIX) {
            if (cfg.accentColor != Color.WHITE) {
                c.drawCircle(x + size * 0.3f, baseline - size * 0.35f, size * 0.3f, fill(cfg.accentColor))
                x += size
            }
            drawDotText(c, text, x, baseline, size, color, area.right - pad)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (!onLight) setShadowLayer(size * 0.3f, 0f, 0f, 0x66000000)
            }
            val fitted = ellipsize(text, p, area.right - pad - x)
            c.drawText(fitted, x, baseline, p)
        }
    }

    private fun ellipsize(t: String, p: Paint, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var s = t
        while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    /** Dot-matrix lettering: rasterise the text at ~7px tall, then draw each lit pixel as a dot. */
    private fun drawDotText(c: Canvas, text: String, x: Float, baseline: Float, size: Float, color: Int, maxX: Float) {
        val small = Paint().apply { isAntiAlias = false; textSize = 8f; typeface = Typeface.MONOSPACE; this.color = Color.WHITE }
        val tw = small.measureText(text).toInt().coerceAtLeast(1) + 1
        val bmp = Bitmap.createBitmap(tw, 10, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 0f, 8f, small)
        val cell = size / 8f
        val dot = fill(color)
        for (py in 0 until 10) for (px in 0 until tw) {
            val dx = x + px * cell
            if (dx > maxX) break
            if (Color.alpha(bmp.getPixel(px, py)) > 100) c.drawCircle(dx + cell / 2, baseline - 8 * cell + py * cell, cell * 0.42f, dot)
        }
        bmp.recycle()
    }

    // ------------------------------------------------------------------ effects
    /** Sticker outline + drop shadow around a transparent subject layer. */
    private fun sticker(layer: Bitmap, cfg: WidgetConfig): Bitmap {
        if (!cfg.outline && !cfg.shadow) return layer
        val out = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val alpha = layer.extractAlpha()
        val t = dp(cfg.outlineWidth)
        if (cfg.shadow) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x73000000
                maskFilter = BlurMaskFilter(dp(cfg.shadowSoftness).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            val o = dp(cfg.shadowOffset)
            c.drawBitmap(alpha, o * 0.5f, o, sp)
        }
        if (cfg.outline && t > 0) {
            val op = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cfg.outlineColor }
            for (ring in listOf(t, t * 0.5f)) for (k in 0 until 16) {
                val a = Math.PI * 2 * k / 16
                c.drawBitmap(alpha, (cos(a) * ring).toFloat(), (sin(a) * ring).toFloat(), op)
            }
        }
        c.drawBitmap(layer, 0f, 0f, null)
        alpha.recycle(); layer.recycle()
        return out
    }

    private fun dotCell(window: RectF) = max(dp(3.5f), min(window.width(), window.height()) / 42f)

    /** Halftone "dot matrix" look: one dot per cell, sized by brightness, alpha preserved for cutouts. */
    private fun dotMatrix(src: Bitmap, cell: Float): Bitmap {
        val gw = (src.width / cell).toInt().coerceAtLeast(1)
        val gh = (src.height / cell).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, gw, gh, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val v = px[y * gw + x]
            val a = Color.alpha(v)
            if (a < 30) continue
            val lum = (0.299f * Color.red(v) + 0.587f * Color.green(v) + 0.114f * Color.blue(v)) / 255f
            p.color = Color.argb(a, 255, 255, 255)
            c.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, cell * 0.48f * (0.18f + 0.82f * lum), p)
        }
        small.recycle()
        return out
    }

    private fun mono(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, monoPaint())
        b.recycle()
        return out
    }

    private fun monoPaint() = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private fun fastBlur(src: Bitmap): Bitmap {
        val w = 24.coerceAtMost(src.width)
        val h = (src.height * w / src.width.toFloat()).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(src, w, h, true)
        val mid = Bitmap.createScaledBitmap(tiny, w * 4, h * 4, true)
        tiny.recycle()
        return mid
    }

    /** Centre of mass of the subject (0..1), used to keep the subject in frame when cropping. */
    private fun focalPoint(cut: Bitmap): PointF {
        val s = Bitmap.createScaledBitmap(cut, 32, (32f * cut.height / cut.width).toInt().coerceAtLeast(1), false)
        var sx = 0f; var sy = 0f; var n = 0f
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val a = Color.alpha(s.getPixel(x, y)) / 255f
            sx += x * a; sy += y * a; n += a
        }
        val pt = if (n > 0) PointF((sx / n + 0.5f) / s.width, (sy / n + 0.5f) / s.height) else PointF(0.5f, 0.45f)
        s.recycle()
        return pt
    }

    // ------------------------------------------------------------------ helpers
    private fun describe(p: Photo?): String {
        if (p == null) return ""
        val date = if (p.dateTaken > 0) DateFormat.getDateInstance(DateFormat.LONG).format(Date(p.dateTaken)) else ""
        return listOfNotNull(p.bucketName, date.ifBlank { null }).joinToString(", ")
    }

    companion object {
        private val FRAME_SHAPES = setOf(Shape.POLAROID, Shape.FILM)
        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        }

        /** Where to draw a [sw]x[sh] image so it fits/covers [dst]; [focal] (0..1) keeps a point in view when cropping. */
        fun mapRect(sw: Int, sh: Int, dst: RectF, fit: Fit, focal: PointF?): RectF {
            val s = if (fit == Fit.FIT) min(dst.width() / sw, dst.height() / sh) else max(dst.width() / sw, dst.height() / sh)
            val w = sw * s
            val h = sh * s
            if (fit != Fit.SMART_FILL || focal == null) {
                return RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
            }
            val left = (dst.centerX() - focal.x * w).coerceIn(dst.right - w, dst.left)
            val top = (dst.centerY() - focal.y * h).coerceIn(dst.bottom - h, dst.top)
            return RectF(left, top, left + w, top + h)
        }

        fun collageCells(n: Int, a: RectF, gap: Float): List<RectF> {
            val wide = a.width() >= a.height()
            fun r(l: Float, t: Float, rr: Float, b: Float) = RectF(l, t, rr, b)
            val mx = a.centerX(); val my = a.centerY(); val g = gap / 2
            return when (n) {
                1 -> listOf(RectF(a))
                2 -> if (wide) listOf(r(a.left, a.top, mx - g, a.bottom), r(mx + g, a.top, a.right, a.bottom))
                else listOf(r(a.left, a.top, a.right, my - g), r(a.left, my + g, a.right, a.bottom))
                3 -> if (wide) listOf(r(a.left, a.top, mx - g, a.bottom), r(mx + g, a.top, a.right, my - g), r(mx + g, my + g, a.right, a.bottom))
                else listOf(r(a.left, a.top, a.right, my - g), r(a.left, my + g, mx - g, a.bottom), r(mx + g, my + g, a.right, a.bottom))
                else -> listOf(
                    r(a.left, a.top, mx - g, my - g), r(mx + g, a.top, a.right, my - g),
                    r(a.left, my + g, mx - g, a.bottom), r(mx + g, my + g, a.right, a.bottom),
                )
            }
        }

        fun shapePath(shape: Shape, r: RectF, radius: Float): Path {
            val p = Path()
            val s = min(r.width(), r.height())
            val cx = r.centerX(); val cy = r.centerY()
            when (shape) {
                Shape.ROUNDED, Shape.POLAROID, Shape.FILM -> p.addRoundRect(r, radius.coerceAtMost(s / 2), radius.coerceAtMost(s / 2), Path.Direction.CW)
                Shape.CIRCLE -> p.addCircle(cx, cy, s / 2, Path.Direction.CW)
                Shape.SQUIRCLE -> {
                    val a = r.width() / 2; val b = r.height() / 2; val n = 4.0
                    for (i in 0..120) {
                        val t = Math.PI * 2 * i / 120
                        val ct = cos(t); val st = sin(t)
                        val x = cx + a * sign(ct) * abs(ct).pow(2 / n)
                        val y = cy + b * sign(st) * abs(st).pow(2 / n)
                        if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                    }
                    p.close()
                }
                Shape.HEART -> {
                    val x = cx - s / 2; val y = cy - s / 2
                    p.moveTo(x + s / 2, y + s * 0.92f)
                    p.cubicTo(x + s * 0.1f, y + s * 0.62f, x - s * 0.02f, y + s * 0.25f, x + s * 0.25f, y + s * 0.1f)
                    p.cubicTo(x + s * 0.38f, y + s * 0.04f, x + s * 0.48f, y + s * 0.14f, x + s / 2, y + s * 0.24f)
                    p.cubicTo(x + s * 0.52f, y + s * 0.14f, x + s * 0.62f, y + s * 0.04f, x + s * 0.75f, y + s * 0.1f)
                    p.cubicTo(x + s * 1.02f, y + s * 0.25f, x + s * 0.9f, y + s * 0.62f, x + s / 2, y + s * 0.92f)
                    p.close()
                }
                Shape.FLOWER -> {
                    val rr = s / 2
                    for (i in 0..240) {
                        val t = Math.PI * 2 * i / 240
                        val k = rr * (0.84 + 0.16 * cos(8 * t))
                        val x = cx + k * cos(t); val y = cy + k * sin(t)
                        if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                    }
                    p.close()
                }
            }
            return p
        }
    }
}
