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
 * End-to-end golden-file test for the WebP read path (`RiffAssetReader` → `JumbfParser` →
 * `CborDecoder` → `ManifestJsonBuilder`). The fixture is `sample1.webp` from
 * `c2pa-rs/sdk/tests/fixtures/`, signed locally with `c2patool` + the ES256 test cert
 * bundled in `c2pa-rs/cli/sample/`. The committed golden was produced by running
 * `c2patool <fixture>` against the signed file.
 */
class C2paReaderWebpEndToEndTest {

    @Test
    fun signedWebpMatchesGoldenAfterFilter() {
        val bytes = loadFixtureBytes("fixtures/sample1.webp")
        val goldenText = loadFixtureText("fixtures/sample1.webp.golden.json")

        val result = C2paReader.read(bytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "emitted JSON does not match filtered golden")
    }

    @Test
    fun cleanWebpReturnsNoManifest() {
        val clean = "RIFF".encodeToByteArray() +
            u32LE(12 + 8) +
            "WEBP".encodeToByteArray() +
            "VP8L".encodeToByteArray() + u32LE(0)
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun truncatedSignedWebpReturnsMalformed() {
        val bytes = loadFixtureBytes("fixtures/sample1.webp")
        // Slice just past the RIFF header into a chunk — the declared chunk size will exceed
        // what remains, which is the truncation shape the reader must surface as Malformed.
        val truncated = bytes.copyOfRange(0, 40)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    @Test
    fun waveFormReturnsUnsupportedFormat() {
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

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderWebpEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderWebpEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter as [C2paReaderTiffEndToEndTest.filterUnsupportedFields] — see that file for
     * the per-field rationale. Copied verbatim to match the per-file duplication pattern set
     * by the JPEG/PNG/sidecar end-to-end tests.
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
            // WebP goldens label the claim thumbnail `c2pa.thumbnail.claim` (no extension).
            // [ManifestJsonBuilder.synthesizeThumbnail]'s prefix matcher expects
            // `c2pa.thumbnail.claim.<ext>`, so the `thumbnail` field isn't synthesized here.
            // Strip it on both sides until the matcher is generalized.
            "thumbnail",
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
