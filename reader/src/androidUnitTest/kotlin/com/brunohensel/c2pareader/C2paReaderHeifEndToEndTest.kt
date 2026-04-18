package com.brunohensel.c2pareader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test for the HEIF/HEIC read path (`BmffAssetReader` → `JumbfParser` →
 * `CborDecoder` → `ManifestJsonBuilder`).
 *
 * Unlike the JPEG/PNG/sidecar end-to-end tests, this suite currently has no positive
 * golden-file case: `c2pa-rs/sdk/tests/fixtures/` does not ship a pre-signed HEIC/HEIF/AVIF
 * fixture (the SDK integration tests sign `sample1.heic` on-the-fly using built-in test
 * certificates), and signing one ourselves would require `c2patool` / `cargo`, which are not
 * available in this environment. Positive coverage is therefore driven entirely by the
 * synthetic-fixture tests in `commonTest/asset/BmffAssetReaderTest.kt`. Once a pre-signed
 * HEIC fixture is produced, add a positive golden test here following the pattern of
 * [C2paReaderPngEndToEndTest].
 *
 * The negative cases below still validate the orchestrator wiring end-to-end.
 */
class C2paReaderHeifEndToEndTest {

    @Test
    fun mp4MajorBrandReturnsUnsupportedFormat() {
        // Synthesize a minimal ISOBMFF file whose ftyp brand is `mp42` (video). `FormatDetector`
        // must reject it before the BMFF reader runs, so the orchestrator returns
        // UnsupportedFormat — not Malformed, which is reserved for structural corruption.
        val mp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,                   // ftyp box size = 20
            0x66, 0x74, 0x79, 0x70,                   // "ftyp"
            0x6D, 0x70, 0x34, 0x32,                   // major brand "mp42"
            0x00, 0x00, 0x00, 0x00,                   // minor version
            0x6D, 0x70, 0x34, 0x32,                   // one compatible brand
        )
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), C2paReader.read(mp4))
    }

    @Test
    fun heicWithNoC2paUuidBoxReturnsNoManifest() {
        // Minimal still-image HEIC: ftyp(heic) + an mdat stub. Detector classifies as Heif,
        // BmffAssetReader walks to EOF without finding a C2PA uuid box → NoManifest.
        val heic = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,                   // ftyp box size = 20
            0x66, 0x74, 0x79, 0x70,                   // "ftyp"
            0x68, 0x65, 0x69, 0x63,                   // major brand "heic"
            0x00, 0x00, 0x00, 0x00,                   // minor version
            0x6D, 0x69, 0x66, 0x31,                   // compat "mif1"
            0x00, 0x00, 0x00, 0x10,                   // mdat box size = 16
            0x6D, 0x64, 0x61, 0x74,                   // "mdat"
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // 8 payload bytes
        )
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(heic))
    }

    @Test
    fun truncatedHeicReturnsMalformed() {
        // Valid ftyp followed by a uuid box whose declared size far exceeds the buffer —
        // exactly the truncation shape the BMFF walker must surface as Malformed.
        val heic = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,                   // ftyp box size = 20
            0x66, 0x74, 0x79, 0x70,                   // "ftyp"
            0x68, 0x65, 0x69, 0x63,                   // major brand "heic"
            0x00, 0x00, 0x00, 0x00,                   // minor version
            0x6D, 0x69, 0x66, 0x31,                   // compat "mif1"
            0x00, 0x00, 0x10, 0x00,                   // uuid box size = 4096 (too big)
            0x75, 0x75, 0x69, 0x64,                   // "uuid"
        )
        val result = C2paReader.read(heic)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }
}
