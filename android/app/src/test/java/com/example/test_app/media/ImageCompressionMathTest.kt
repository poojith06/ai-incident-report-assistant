package com.example.test_app.media

import com.example.test_app.ai.llama.ImageScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure maths behind `ImageCompressor` (decode size, EXIF orientation, final size) and the photo-count rules. */
class ImageCompressionMathTest {
    // ---- EXIF orientation ----

    /** Pixel (x, y) of a w x h image after "mirror first, then rotate clockwise by [degrees]": its place in the displayed image. */
    private fun place(x: Int, y: Int, w: Int, h: Int, mirror: Boolean, degrees: Int): Pair<Int, Int> {
        var px = if (mirror) w - 1 - x else x
        var py = y
        var cw = w
        var ch = h
        repeat(degrees / 90) {
            val nx = ch - 1 - py
            val ny = px
            px = nx; py = ny
            val t = cw; cw = ch; ch = t
        }
        return px to py
    }

    @Test fun everyExifOrientationMatchesItsDefinition() {
        val w = 4
        val h = 3
        // EXIF spec: (stored x, y) -> displayed position
        val expected = mapOf<Int, (Int, Int) -> Pair<Int, Int>>(
            1 to { x, y -> x to y },
            2 to { x, y -> w - 1 - x to y }, // mirror horizontal
            3 to { x, y -> w - 1 - x to h - 1 - y }, // rotate 180
            4 to { x, y -> x to h - 1 - y }, // mirror vertical
            5 to { x, y -> y to x }, // transpose
            6 to { x, y -> h - 1 - y to x }, // rotate 90 clockwise
            7 to { x, y -> h - 1 - y to w - 1 - x }, // transverse
            8 to { x, y -> y to w - 1 - x }, // rotate 270 clockwise
        )
        for ((orientation, f) in expected) {
            for (x in 0 until w) for (y in 0 until h) {
                val got = place(x, y, w, h, ImageScaling.exifMirrored(orientation), ImageScaling.exifRotationDegrees(orientation))
                assertEquals("orientation $orientation at ($x,$y)", f(x, y), got)
            }
        }
    }

    @Test fun mirrorFlagsAndUnknownOrientations() {
        assertEquals(listOf(false, true, false, true, true, false, true, false), (1..8).map { ImageScaling.exifMirrored(it) })
        for (o in listOf(0, -1, 9, 99)) {
            assertFalse(ImageScaling.exifMirrored(o))
            assertEquals(0, ImageScaling.exifRotationDegrees(o))
        }
    }

    @Test fun rotatedPhotosSwapWidthAndHeightBeforeFitting() {
        for (o in listOf(5, 6, 7, 8)) {
            val d = ImageScaling.exifRotationDegrees(o)
            val p = ImageScaling.plan(4000, 3000, d)
            assertEquals("orientation $o", 384 to 512, p.finalWidth to p.finalHeight)
            assertEquals(512 to 384, p.scaledWidth to p.scaledHeight)
        }
        for (o in listOf(1, 2, 3, 4)) {
            val p = ImageScaling.plan(4000, 3000, ImageScaling.exifRotationDegrees(o))
            assertEquals("orientation $o", 512 to 384, p.finalWidth to p.finalHeight)
        }
    }

    // ---- size maths ----

    @Test fun theDecodeNeverTakesTheFullResolutionBitmap() {
        for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 8000 to 6000, 12000 to 9000, 1024 to 768, 513 to 100, 100 to 4000)) {
            val sample = ImageScaling.sampleSize(w, h)
            val longSide = maxOf(w, h) / sample
            assertTrue("$w x $h sample $sample", longSide >= ImageScaling.MAX_SIDE)
            assertTrue("$w x $h sample $sample decodes at most ~2x the target", longSide < 2 * ImageScaling.MAX_SIDE || sample == 1)
            assertTrue("power of two", sample and (sample - 1) == 0)
            if (maxOf(w, h) >= 2 * ImageScaling.MAX_SIDE) assertTrue("a big photo is subsampled: $w x $h", sample > 1)
        }
    }

    @Test fun smallPhotosAreNotSubsampledOrUpscaled() {
        assertEquals(1, ImageScaling.sampleSize(400, 300))
        assertEquals(1, ImageScaling.sampleSize(1023, 600))
        assertEquals(400 to 300, ImageScaling.fit(400, 300))
    }

    @Test fun theCompressedPhotoHasALongSideOfAtMost512AndKeepsTheAspectRatio() {
        for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 1 to 5000, 5000 to 1, 513 to 512, 512 to 512, 7 to 5)) {
            for (deg in listOf(0, 90, 180, 270)) {
                val sample = ImageScaling.sampleSize(w, h)
                val p = ImageScaling.plan(maxOf(1, w / sample), maxOf(1, h / sample), deg) // the decoder never returns a 0 px side
                assertTrue("$w x $h/$deg", maxOf(p.finalWidth, p.finalHeight) <= ImageScaling.MAX_SIDE)
                assertTrue(p.finalWidth >= 1 && p.finalHeight >= 1)
            }
        }
        val (fw, fh) = ImageScaling.fit(4032, 3024)
        assertEquals(512 to 384, fw to fh)
    }

    @Test fun jpegQualityIsAbout80AndTheSideIs512() {
        assertEquals(80, ImageScaling.JPEG_QUALITY)
        assertEquals(512, ImageScaling.MAX_SIDE)
    }

    // ---- how many photos ----

    @Test fun atMostThreePhotosPerReport() {
        assertEquals(PhotoPicks.Decision(3, 0), PhotoPicks.accept(0, 3))
        assertEquals(PhotoPicks.Decision(1, 0), PhotoPicks.accept(0, 1))
        assertEquals(PhotoPicks.Decision(3, 2), PhotoPicks.accept(0, 5))
        assertEquals(PhotoPicks.Decision(1, 1), PhotoPicks.accept(2, 2))
        assertEquals(PhotoPicks.Decision(0, 2), PhotoPicks.accept(3, 2))
        assertEquals(PhotoPicks.Decision(0, 0), PhotoPicks.accept(0, 0))
        assertEquals("a cancelled pick changes nothing", PhotoPicks.Decision(0, 0), PhotoPicks.accept(2, -1))
    }

    @Test fun droppedPhotosAreExplainedInPlainWords() {
        assertEquals(null, PhotoPicks.droppedNotice(PhotoPicks.accept(0, 3)))
        assertEquals("Only 3 photos can be added to one report; 1 was left out.", PhotoPicks.droppedNotice(PhotoPicks.accept(0, 4)))
        assertEquals("Only 3 photos can be added to one report; 2 were left out.", PhotoPicks.droppedNotice(PhotoPicks.accept(1, 4)))
    }
}
