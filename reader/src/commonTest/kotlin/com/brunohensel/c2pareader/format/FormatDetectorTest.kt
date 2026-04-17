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
