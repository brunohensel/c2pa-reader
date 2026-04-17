package com.brunohensel.c2pareader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end golden-file test for the JPEG read path. Loads a real C2PA-tagged JPEG, runs it
 * through the full pipeline (JpegAssetReader → JumbfParser → CborDecoder → ManifestJsonBuilder),
 * then compares the emitted JSON against a committed golden after stripping fields the library
 * explicitly doesn't produce in Phase 1 (signature_info, validation_state, validation_results,
 * validation_status — all of which require COSE+X.509 parsing or a cryptographic validation
 * pipeline that's out of scope).
 *
 * Lives in `androidUnitTest` (not `commonTest`) because classpath resource loading is a JVM-only
 * idiom. Re-homing into `commonTest` with expect/actual loaders is a follow-up once iOS test
 * resource bundling is wired up.
 */
class C2paReaderJpegEndToEndTest {

    @Test
    fun fireflyJpegMatchesGoldenAfterFilter() {
        val jpegBytes = loadFixtureBytes("fixtures/firefly_tabby_cat.jpg")
        val goldenText = loadFixtureText("fixtures/firefly_tabby_cat.golden.json")

        val result = C2paReader.read(jpegBytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterCryptoAndValidation()
        val golden = Json.parseToJsonElement(goldenText).filterCryptoAndValidation()

        assertEquals(golden, emitted, "emitted JSON does not match filtered golden")
    }

    @Test
    fun cleanJpegReturnsNoManifest() {
        // Minimal JPEG: SOI + APP0 (JFIF) + EOI. No APP11, so no manifest.
        val clean = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                        // SOI
            0xFF.toByte(), 0xE0.toByte(),                        // APP0
            0x00, 0x10,                                          // length = 16
            0x4A, 0x46, 0x49, 0x46, 0x00,                        // "JFIF\0"
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // JFIF header
            0xFF.toByte(), 0xD9.toByte(),                        // EOI
        )
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun nonJpegReturnsUnsupportedFormat() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), C2paReader.read(png))
    }

    @Test
    fun truncatedFireflyJpegReturnsMalformed() {
        val jpegBytes = loadFixtureBytes("fixtures/firefly_tabby_cat.jpg")
        // Cut off somewhere in the middle of the APP11 JUMBF fragments. The declared segment
        // length will exceed what remains, which is exactly the truncation shape the reader
        // must detect.
        val truncated = jpegBytes.copyOfRange(0, 200)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result"
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderJpegEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderJpegEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Recursively strips the four fields the library doesn't emit in Phase 1. Applied to both
     * sides of the comparison so differences in the remaining structure surface clearly.
     */
    private fun JsonElement.filterCryptoAndValidation(): JsonElement {
        val removeKeys = setOf(
            "signature_info",
            "validation_state",
            "validation_results",
            "validation_status",
        )
        return when (this) {
            is JsonObject -> {
                val filtered: Map<String, JsonElement> = this
                    .filterKeys { it !in removeKeys }
                    .mapValues { (_, v) -> v.filterCryptoAndValidation() }
                JsonObject(filtered)
            }
            is JsonArray -> JsonArray(map { it.filterCryptoAndValidation() })
            else -> this
        }
    }
}
