package com.example.test_app.ai.llama

/**
 * Pure (JVM-testable) maths for preparing photos for Gemma: the model sees photos with a long side of at most [MAX_SIDE] px
 * (measured on the QIDK: a 2746x4032 photo costs ~1279 prompt tokens and 23 s, the 512 px version ~276 tokens and 4.2 s;
 * `idk-test/MODELS_USAGE.md` section 5c). The Android side (`GemmaImageAnalysis.loadRgb`) does the decoding with these numbers.
 */
object ImageScaling {
    const val MAX_SIDE = 512

    /** JPEG quality of the compressed copy that is stored with the report (`media/ImageCompressor`). */
    const val JPEG_QUALITY = 80

    /** Size after scaling so that the long side is at most [maxSide]; aspect ratio kept, never upscaled, each side at least 1. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSide > 0) { "bad size ${width}x$height / $maxSide" }
        val longSide = maxOf(width, height)
        if (longSide <= maxSide) return width to height
        val scale = maxSide.toDouble() / longSide
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /**
     * Largest power-of-two `inSampleSize` for `BitmapFactory` that still decodes at least [maxSide] px on the long side
     * (the exact downscale is done afterwards, so the photo is never decoded at full size).
     * The decoded size is at most about 2x [maxSide] on the long side, whatever the original resolution.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        require(width > 0 && height > 0 && maxSide > 0)
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /**
     * True for the EXIF orientations that include a horizontal mirror (2, 4, 5, 7). The bitmap is mirrored FIRST and then rotated by
     * [exifRotationDegrees] (transpose = mirror + 270, transverse = mirror + 90).
     */
    fun exifMirrored(orientation: Int): Boolean = orientation == EXIF_FLIP_HORIZONTAL || orientation == EXIF_FLIP_VERTICAL ||
        orientation == EXIF_TRANSPOSE || orientation == EXIF_TRANSVERSE

    /** Rotation in degrees (clockwise) that the EXIF orientation value asks for (after the mirror of [exifMirrored], when there is one). */
    fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
        EXIF_ROTATE_90, EXIF_TRANSVERSE -> 90
        EXIF_ROTATE_180, EXIF_FLIP_VERTICAL -> 180
        EXIF_ROTATE_270, EXIF_TRANSPOSE -> 270
        else -> 0
    }

    /** Sizes for decoding a photo of [width]x[height] that must be rotated by [degrees]: `scaled` before the rotation, `final` after it. */
    data class Plan(val scaledWidth: Int, val scaledHeight: Int, val finalWidth: Int, val finalHeight: Int)

    fun plan(width: Int, height: Int, degrees: Int, maxSide: Int = MAX_SIDE): Plan {
        val swap = degrees == 90 || degrees == 270
        val (fw, fh) = fit(if (swap) height else width, if (swap) width else height, maxSide)
        return Plan(if (swap) fh else fw, if (swap) fw else fh, fw, fh)
    }

    /** `Bitmap.getPixels` ARGB ints to tightly packed RGB bytes (alpha dropped), the layout `mtmd_bitmap_init` expects. */
    fun argbToRgb(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 3)
        var o = 0
        for (p in argb) {
            out[o++] = (p shr 16).toByte()
            out[o++] = (p shr 8).toByte()
            out[o++] = p.toByte()
        }
        return out
    }

    // android.media.ExifInterface.ORIENTATION_* values (inlined to keep this object free of Android classes)
    private const val EXIF_FLIP_HORIZONTAL = 2
    private const val EXIF_ROTATE_180 = 3
    private const val EXIF_FLIP_VERTICAL = 4
    private const val EXIF_TRANSPOSE = 5
    private const val EXIF_ROTATE_90 = 6
    private const val EXIF_TRANSVERSE = 7
    private const val EXIF_ROTATE_270 = 8
}
