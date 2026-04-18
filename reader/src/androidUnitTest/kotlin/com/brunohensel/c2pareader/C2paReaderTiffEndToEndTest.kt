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
 * End-to-end golden-file test for the TIFF read path (`TiffAssetReader` → `JumbfParser` →
 * `CborDecoder` → `ManifestJsonBuilder`). The fixture is
 * `c2pa-rs/sdk/benches/fixtures/100kb-signed.tiff` (pre-signed). The golden JSON was produced
 * locally via `c2patool 100kb-signed.tiff`.
 */
class C2paReaderTiffEndToEndTest {

    @Test
    fun signedTiffMatchesGoldenAfterFilter() {
        val bytes = loadFixtureBytes("fixtures/100kb-signed.tiff")
        val goldenText = loadFixtureText("fixtures/100kb-signed.tiff.golden.json")

        val result = C2paReader.read(bytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "emitted JSON does not match filtered golden")
    }

    @Test
    fun cleanTiffReturnsNoManifest() {
        val clean = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00,                   // "II" + magic 42
            0x08, 0x00, 0x00, 0x00,                   // firstIfdOffset = 8
            0x00, 0x00,                               // entryCount = 0
            0x00, 0x00, 0x00, 0x00,                   // nextIfdOffset = 0
        )
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun truncatedSignedTiffReturnsMalformed() {
        val bytes = loadFixtureBytes("fixtures/100kb-signed.tiff")
        val truncated = bytes.copyOfRange(0, 20)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderTiffEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderTiffEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter as [C2paReaderPngEndToEndTest.filterUnsupportedFields], plus two Phase 3
     * additions:
     *
     *  - `claim_version` — current [ManifestJsonBuilder] only emits this for claim v2 boxes;
     *    newer `c2pa-rs` readers emit it for v1 too (`"claim_version": 1`). Filter on both
     *    sides until the builder aligns.
     *  - Assertion entries labelled `c2pa.hash.*` — the builder drops these as validation-only
     *    integrity data, but the `c2pa-rs` reader schema surfaces them. Drop on both sides until
     *    the builder stops filtering.
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
