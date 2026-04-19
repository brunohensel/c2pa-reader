package com.brunohensel.c2pareader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end golden-file test for the HEIF/HEIC read path (`BmffAssetReader` → `JumbfParser`
 * → `CborDecoder` → `ManifestJsonBuilder`). The fixture is `sample1.heic` from
 * `c2pa-rs/sdk/tests/fixtures/`, signed locally with `c2patool` + the ES256 test cert
 * bundled in `c2pa-rs/cli/sample/`. The committed golden was produced by running
 * `c2patool <fixture>` against the signed file.
 *
 * Lives in `androidUnitTest` because classpath resource loading is a JVM-only idiom.
 */
class C2paReaderHeifEndToEndTest {

    @Test
    fun signedHeicMatchesGoldenAfterFilter() {
        val bytes = loadFixtureBytes("fixtures/sample1.heic")
        val goldenText = loadFixtureText("fixtures/sample1.heic.golden.json")

        val result = C2paReader.read(bytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "emitted JSON does not match filtered golden")
    }

    @Test
    fun mp4MajorBrandReturnsUnsupportedFormat() {
        // Synthesize a minimal ISOBMFF file whose ftyp brand is `mp42` (video). FormatDetector
        // must reject it before the BMFF reader runs — orchestrator returns UnsupportedFormat.
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
        val heic = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,                   // ftyp box size = 20
            0x66, 0x74, 0x79, 0x70,                   // "ftyp"
            0x68, 0x65, 0x69, 0x63,                   // major brand "heic"
            0x00, 0x00, 0x00, 0x00,                   // minor version
            0x6D, 0x69, 0x66, 0x31,                   // compat "mif1"
            0x00, 0x00, 0x00, 0x10,                   // mdat box size = 16
            0x6D, 0x64, 0x61, 0x74,                   // "mdat"
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(heic))
    }

    @Test
    fun truncatedSignedHeicReturnsMalformed() {
        val bytes = loadFixtureBytes("fixtures/sample1.heic")
        val truncated = bytes.copyOfRange(0, 200)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderHeifEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderHeifEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter as [C2paReaderPngEndToEndTest.filterUnsupportedFields] — strips fields the
     * reader library does not yet compute (cryptographic verification, ingredient resolution,
     * claim-v2 created/redacted flags, claim_generator_info normalization). See the PNG test
     * for the per-field rationale. Copied verbatim here; refactoring into a shared helper is
     * deferred.
     */
    private fun JsonElement.filterUnsupportedFields(): JsonElement {
        val removeKeys = setOf(
            "signature_info",
            "validation_state",
            "validation_results",
            "validation_status",
            "ingredients",
            "created_assertions",
            "redacted_assertions",
            "created",
            "claim_generator_info",
            "claim_version",
        )
        return when (this) {
            is JsonObject -> {
                val filtered: Map<String, JsonElement> = this
                    .filterKeys { it !in removeKeys }
                    .mapValues { (k, v) ->
                        if (k == "assertions" && v is JsonArray) {
                            JsonArray(
                                v.filterNot { entry ->
                                    val label = (entry as? JsonObject)?.get("label")
                                    if (label !is JsonPrimitive) return@filterNot false
                                    val name = label.content
                                    name.startsWith("c2pa.ingredient.") ||
                                        name.startsWith("c2pa.hash.")
                                }.map { it.filterUnsupportedFields() }
                            )
                        } else {
                            v.filterUnsupportedFields()
                        }
                    }
                JsonObject(filtered)
            }
            is JsonArray -> JsonArray(map { it.filterUnsupportedFields() })
            else -> this
        }
    }
}
