package com.saikat.blurpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Target(val label: String) { Home("Home"), Lock("Lock"), Both("Both") }

object WallpaperSetter {

    /** Blur radius at full slider, as a fraction of image width (~54 px on a 1080 px screen). */
    private const val MAX_BLUR_FRACTION = 0.05f

    const val MAX_ZOOM = 4f

    /** Extra saturation at full blur (0.25 = +25%). Tune to taste. */
    private const val SATURATION_BOOST = 0.25f

    /** Black overlay opacity at full "darkness" slider. */
    private const val MAX_DARK_ALPHA = 0.7f

    fun screenSize(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Copies a picked/shared image into app storage so it survives restarts. */
    fun copyTo(context: Context, uri: Uri, dest: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } != null
    } catch (e: Exception) {
        false
    }

    /** Decodes the stored image (uncropped), downsampled to a bit more than screen size, EXIF-rotated. */
    fun decodeSource(context: Context, file: File): Bitmap? = try {
        val (sw, sh) = screenSize(context)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val needW = (sw * 1.5f).toInt()
            val needH = (sh * 1.5f).toInt()
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= needW && bounds.outHeight / (sample * 2) >= needH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.path, opts)
            if (bmp != null) {
                val degrees = exifDegrees(file.path)
                if (degrees != 0) {
                    val m = Matrix().apply { postRotate(degrees.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
            }
            bmp
        }
    } catch (e: Exception) {
        null
    }

    /**
     * How far the image may be panned, as a fraction of the frame size, for a given zoom.
     * Frame aspect = width / height.
     */
    fun maxPan(imgW: Int, imgH: Int, frameAspect: Float, zoom: Float): Pair<Float, Float> {
        val imgAspect = imgW.toFloat() / imgH
        val coverW = max(1f, imgAspect / frameAspect) * zoom
        val coverH = max(1f, frameAspect / imgAspect) * zoom
        return ((coverW - 1f) / 2f) to ((coverH - 1f) / 2f)
    }

    /** Draws the zoomed/panned crop of [src] into a new outW x outH bitmap. */
    fun render(src: Bitmap, outW: Int, outH: Int, zoom: Float, panX: Float, panY: Float): Bitmap {
        val bw = src.width.toFloat()
        val bh = src.height.toFloat()
        val scale = max(outW / bw, outH / bh) * zoom
        val (mx, my) = maxPan(src.width, src.height, outW.toFloat() / outH, zoom)
        val px = panX.coerceIn(-mx, mx)
        val py = panY.coerceIn(-my, my)
        val dx = (outW - bw * scale) / 2f + px * outW
        val dy = (outH - bh * scale) / 2f + py * outH

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Full pipeline: crop (zoom/pan) -> blur -> darken. Used for both preview and final. */
    fun process(
        src: Bitmap, outW: Int, outH: Int,
        zoom: Float, panX: Float, panY: Float,
        blur: Float, dark: Float
    ): Bitmap {
        val blurred = blur(render(src, outW, outH, zoom, panX, panY), blur)
        // iOS also lifts color under its blur, so boost saturation in proportion to blur.
        val vivid = if (blur > 0.005f) saturate(blurred, 1f + SATURATION_BOOST * blur) else blurred
        return darken(vivid, dark)
    }

    private fun saturate(src: Bitmap, saturation: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** strength is 0..1. Radius scales with image width, so preview and final look the same. */
    private fun blur(src: Bitmap, strength: Float): Bitmap {
        val radius = strength * MAX_BLUR_FRACTION * src.width
        if (radius < 0.5f) return src

        // Work on a downscaled copy: far cheaper, and the result is blurry anyway.
        val scale = max(1, (radius / 4f).toInt())
        val w = max(1, src.width / scale)
        val h = max(1, src.height / scale)
        val small = Bitmap.createScaledBitmap(src, w, h, true)

        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        val tmp = IntArray(w * h)

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

    private fun darken(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.005f) return src
        val m = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val alpha = (amount * MAX_DARK_ALPHA * 255f).roundToInt().coerceIn(0, 255)
        Canvas(m).drawColor(Color.argb(alpha, 0, 0, 0))
        return m
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

    private fun exifDegrees(path: String): Int = try {
        when (ExifInterface(path).getAttributeInt(
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
