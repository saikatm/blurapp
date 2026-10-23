package com.saikat.blurpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Target(val label: String) { Home("Home"), Lock("Lock"), Both("Both") }

object WallpaperSetter {

    /** Blur radius at full slider, as a fraction of image width (~54 px on a 1080 px screen). */
    private const val MAX_BLUR_FRACTION = 0.05f

    /** Decodes the image, fixes rotation, and center-crops it to the screen's size. */
    fun load(context: Context, uri: Uri): Bitmap? {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val degrees = resolver.openInputStream(uri)?.use { exifDegrees(it) } ?: 0
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return centerCrop(bmp, screenW, screenH)
    }

    /** Small copy used for the live preview so the slider stays smooth. */
    fun smallPreview(src: Bitmap, maxSide: Int = 480): Bitmap {
        val scale = maxSide.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
            true
        )
    }

    /** strength is 0..1. Radius scales with image width, so preview and final look the same. */
    fun blur(src: Bitmap, strength: Float): Bitmap {
        val radius = strength * MAX_BLUR_FRACTION * src.width
        if (radius < 0.5f) return src

        // Work on a downscaled copy: far cheaper, and the result is blurry anyway.
        val scale = max(1, (radius / 4f).toInt())
        val w = max(1, src.width / scale)
        val h = max(1, src.height / scale)
        val small = Bitmap.createScaledBitmap(src, w, h, true)

        var px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        var tmp = IntArray(w * h)

        val sigma = radius / scale
        val r = max(1, (sqrt(4f * sigma * sigma + 1f).roundToInt() - 1) / 2)

        // Three box-blur passes approximate a Gaussian.
        repeat(3) {
            blurLines(px, tmp, lines = h, n = w, lineStep = w, pixStep = 1, r = r)
            blurLines(tmp, px, lines = w, n = h, lineStep = 1, pixStep = w, r = r)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }

    fun apply(context: Context, bitmap: Bitmap, target: Target): Boolean = try {
        val flags = when (target) {
            Target.Home -> WallpaperManager.FLAG_SYSTEM
            Target.Lock -> WallpaperManager.FLAG_LOCK
            Target.Both -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, flags)
        true
    } catch (e: Exception) {
        false
    }

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dx = (w - src.width * scale) / 2f
        val dy = (h - src.height * scale) / 2f
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun exifDegrees(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** One box-blur pass along every line, clamping at the edges. */
    private fun blurLines(
        src: IntArray, dst: IntArray,
        lines: Int, n: Int, lineStep: Int, pixStep: Int, r: Int
    ) {
        val div = 2 * r + 1
        for (l in 0 until lines) {
            val base = l * lineStep
            var rs = 0
            var gs = 0
            var bs = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, n - 1) * pixStep]
                rs += (p shr 16) and 0xFF
                gs += (p shr 8) and 0xFF
                bs += p and 0xFF
            }
            for (i in 0 until n) {
                dst[base + i * pixStep] =
                    (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = src[base + min(i + r + 1, n - 1) * pixStep]
                val sub = src[base + max(i - r, 0) * pixStep]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
