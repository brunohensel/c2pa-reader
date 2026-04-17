package com.brunohensel.c2pareader.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDetectorTest {
    @Test
    fun jpegSoiMarkerIsDetected() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(ImageFormat.Jpeg, FormatDetector.detect(bytes))
    }

    @Test
    fun pngSignatureIsDetected() {
        // PNG signature per ISO/IEC 15948: 89 50 4E 47 0D 0A 1A 0A
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals(ImageFormat.Png, FormatDetector.detect(bytes))
    }

    @Test
    fun pngSignatureWithTruncatedTailIsUnknown() {
        // First 7 bytes of the PNG signature — not enough to confirm PNG.
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A,
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun arbitraryBytesAreUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun emptyBytesAreUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf()))
    }

    @Test
    fun singleByteIsUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf(0xFF.toByte())))
    }
}
