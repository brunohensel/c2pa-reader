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
    fun jumbSignatureAtOffsetFourIsSidecar() {
        // Any 4-byte length prefix followed by ASCII "jumb" (0x6A 0x75 0x6D 0x62) — the JUMBF
        // top-level superbox type — is recognized as a standalone `.c2pa` sidecar.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,                   // LBox = 32 (arbitrary, non-zero)
            0x6A, 0x75, 0x6D, 0x62,                   // TBox = "jumb"
            0x00, 0x00, 0x00, 0x00,                   // stub payload
        )
        assertEquals(ImageFormat.C2paSidecar, FormatDetector.detect(bytes))
    }

    @Test
    fun nonJumbAtOffsetFourIsUnknown() {
        // Same shape as above but TBox is "free" — not a JUMBF superbox.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            0x66, 0x72, 0x65, 0x65,
            0x00, 0x00, 0x00, 0x00,
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun sevenByteBufferIsUnknown() {
        // Too short for either PNG signature (8) or a JUMBF box header (8). Should not be
        // misclassified as a sidecar due to an out-of-bounds read.
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x6A, 0x75, 0x6D)
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
