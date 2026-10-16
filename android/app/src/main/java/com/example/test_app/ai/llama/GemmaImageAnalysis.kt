package com.example.test_app.ai.llama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.example.test_app.ai.LlmEngine
import com.example.test_app.ai.MediaPart
import com.example.test_app.ai.Prompts
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.ImageHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Photo analysis with Gemma 4 vision: the photo is decoded, rotated by its EXIF orientation and downscaled to at most
 * [ImageScaling.MAX_SIDE] px on the long side (measured: ~276 instead of ~1279 prompt tokens, 4.2 s instead of 23 s on the QIDK; detail
 * is lost), then described in one short sentence plus up to 5 tags ([Prompts.imageDescription]). The hint feeds the extraction prompt
 * through `ExtractionRequest.imageHints`. Shares the engine with extraction/STT. Latency in this app: not yet measured.
 */
class GemmaImageAnalysis(private val engine: LlmEngine) : ImageAnalysisService {
    override suspend fun analyze(media: MediaItem): ImageHint {
        val image = withContext(Dispatchers.IO) { loadRgb(File(URI(media.localUri))) }
        engine.load()
        val result = engine.generate(Prompts.imageDescription(image), MAX_TOKENS)
        val photo = GemmaText.parsePhoto(result.text) ?: throw ExtractionException("The assistant could not describe the photo.")
        Log.i(
            TAG,
            "photo ${media.id} ${image.width}x${image.height} prefillMs=${result.prefillMs} decodeMs=${result.decodeMs} promptTokens=${result.promptTokens} " +
                "description=\"${photo.description}\" tags=${photo.tags}",
        )
        return ImageHint(media.id, photo.tags, photo.description)
    }

    companion object {
        const val TAG = "GemmaImage"
        const val MAX_TOKENS = 160

        /** Decodes [file] (never at full size), applies the EXIF rotation, scales to <= 512 px and returns tightly packed RGB. */
        fun loadRgb(file: File, maxSide: Int = ImageScaling.MAX_SIDE): MediaPart.Image {
            val path = file.path
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a decodable image: $path" }
            val sampled = BitmapFactory.decodeFile(
                path, BitmapFactory.Options().apply { inSampleSize = ImageScaling.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) },
            ) ?: throw IllegalArgumentException("could not decode $path")
            val degrees = ImageScaling.exifRotationDegrees(
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
            val plan = ImageScaling.plan(sampled.width, sampled.height, degrees, maxSide)
            var bitmap: Bitmap = sampled
            if (plan.scaledWidth != bitmap.width || plan.scaledHeight != bitmap.height) {
                bitmap = Bitmap.createScaledBitmap(bitmap, plan.scaledWidth, plan.scaledHeight, true)
            }
            if (degrees != 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val out = MediaPart.Image(ImageScaling.argbToRgb(pixels), bitmap.width, bitmap.height)
            if (bitmap !== sampled) bitmap.recycle()
            sampled.recycle()
            return out
        }
    }
}
