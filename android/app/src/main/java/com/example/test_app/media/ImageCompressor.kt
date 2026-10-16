package com.example.test_app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.test_app.ai.llama.ImageScaling
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** A picked photo that could not be read or compressed; [message] is shown to the user. */
class ImageCompressionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Compresses a photo picked from the gallery BEFORE it is stored or shown to a model: the original is only read through its content
 * URI (never copied into app storage); the result is a JPEG of at most [ImageScaling.MAX_SIDE] px on the long side at quality
 * [ImageScaling.JPEG_QUALITY], written to `<dir>/<uuid>.jpg`. The photo is never decoded at full size (`BitmapFactory.inSampleSize` from
 * [ImageScaling.sampleSize]); the EXIF orientation (rotation and mirror) is applied to the pixels, so the stored file needs no EXIF.
 * The model reads this same file (`GemmaImageAnalysis.loadRgb`, an already small image), so it gets the same <= 512 px picture as
 * the thumbnails and the report. The size maths is pure (`ai/llama/ImageScaling`, unit-tested); the decoding is Android-only and was
 * not exercised on a device by the JVM tests. See `doc/UI_FLOW.md` and `doc/DECISIONS.md` ADR-053.
 */
class ImageCompressor(private val context: Context, private val dir: File) {
    /** The compressed copy: [file] holds a [width] x [height] JPEG of [bytes] bytes. */
    data class Result(val file: File, val width: Int, val height: Int, val bytes: Long)

    /** Blocking; call off the main thread. */
    fun compress(uri: Uri): Result {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(uri) ?: throw ImageCompressionException("I couldn't open that photo.")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageCompressionException("That file is not a photo I can read.")
            val orientation = runCatching {
                open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val sampled = open().use {
                BitmapFactory.decodeStream(
                    it, null, BitmapFactory.Options().apply { inSampleSize = ImageScaling.sampleSize(bounds.outWidth, bounds.outHeight) },
                )
            } ?: throw ImageCompressionException("I couldn't decode that photo.")
            val degrees = ImageScaling.exifRotationDegrees(orientation)
            val plan = ImageScaling.plan(sampled.width, sampled.height, degrees)
            var bitmap: Bitmap = sampled
            if (plan.scaledWidth != bitmap.width || plan.scaledHeight != bitmap.height) {
                bitmap = Bitmap.createScaledBitmap(bitmap, plan.scaledWidth, plan.scaledHeight, true)
            }
            if (degrees != 0 || ImageScaling.exifMirrored(orientation)) {
                val m = Matrix().apply {
                    if (ImageScaling.exifMirrored(orientation)) postScale(-1f, 1f)
                    if (degrees != 0) postRotate(degrees.toFloat())
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }
            if (bitmap.hasAlpha()) { // JPEG has no alpha: a transparent PNG would turn black
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                bitmap = flat
            }
            dir.mkdirs()
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            try {
                FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, ImageScaling.JPEG_QUALITY, it)) { "JPEG encoder failed" } }
            } catch (e: Exception) {
                file.delete()
                throw ImageCompressionException("I couldn't save that photo.", e)
            }
            return Result(file, bitmap.width, bitmap.height, file.length()).also {
                if (bitmap !== sampled) sampled.recycle()
                bitmap.recycle()
            }
        } catch (e: ImageCompressionException) {
            throw e
        } catch (e: IOException) {
            throw ImageCompressionException("I couldn't read that photo.", e)
        } catch (e: SecurityException) {
            throw ImageCompressionException("I'm not allowed to read that photo.", e)
        } catch (e: OutOfMemoryError) {
            throw ImageCompressionException("That photo is too large to open.")
        }
    }
}
