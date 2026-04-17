package com.brunohensel.c2pareader

import kotlin.test.Test
import kotlin.test.assertEquals

class C2paReaderSmokeTest {
    @Test
    fun emptyInputReturnsUnsupportedFormat() {
        val result = C2paReader.read(byteArrayOf())
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), result)
    }

    @Test
    fun nonJpegInputReturnsUnsupportedFormat() {
        val notAJpeg = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = C2paReader.read(notAJpeg)
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), result)
    }

    @Test
    fun jpegWithoutManifestReturnsNoManifest() {
        // Minimum valid JPEG prefix: only the SOI marker. No APP11 segments, no manifest.
        val cleanJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val result = C2paReader.read(cleanJpeg)
        assertEquals(C2paResult.Failure(C2paError.NoManifest), result)
    }
}
