package com.brunohensel.c2pareader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test for the WebP read path (`RiffAssetReader` → `JumbfParser` → `CborDecoder`
 * → `ManifestJsonBuilder`).
 *
 * No positive golden case is shipped yet: `c2pa-rs/sdk/tests/fixtures/` contains source WebP
 * files but none with an embedded C2PA manifest (they exist to be signed on-the-fly by the
 * SDK's integration tests), and signing one ourselves would require `c2patool` / `cargo`,
 * which aren't available in this environment. Positive coverage for the walker lives in
 * `commonTest/asset/RiffAssetReaderTest.kt` against synthetic fixtures. Add a positive
 * golden test here once a pre-signed WebP fixture exists.
 */
class C2paReaderWebpEndToEndTest {

    @Test
    fun cleanWebpReturnsNoManifest() {
        // Minimal WebP: "RIFF" + size + "WEBP" + one VP8L chunk, no C2PA chunk.
        val clean = "RIFF".encodeToByteArray() +
            u32LE(12 + 8) + // payload = WEBP(4) + chunk header(8) + empty chunk data
            "WEBP".encodeToByteArray() +
            "VP8L".encodeToByteArray() + u32LE(0)
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun truncatedWebpReturnsMalformed() {
        // Valid RIFF header + "WEBP" form, then a chunk whose declared size exceeds what follows.
        val truncated = "RIFF".encodeToByteArray() +
            u32LE(100) +
            "WEBP".encodeToByteArray() +
            "C2PA".encodeToByteArray() + u32LE(1000) + // chunk claims 1000 bytes of payload
            byteArrayOf(0x00, 0x01, 0x02, 0x03)        // …only 4 present
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    @Test
    fun waveFormReturnsUnsupportedFormat() {
        // Synthetic RIFF/WAVE header — FormatDetector must reject it (WebP is the only RIFF
        // form type in Phase 3 scope; audio/video variants fall through to Unknown).
        val wave = "RIFF".encodeToByteArray() + u32LE(4) + "WAVE".encodeToByteArray()
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), C2paReader.read(wave))
    }

    @Test
    fun aviFormReturnsUnsupportedFormat() {
        val avi = "RIFF".encodeToByteArray() + u32LE(4) + "AVI ".encodeToByteArray()
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), C2paReader.read(avi))
    }

    private fun u32LE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
}
