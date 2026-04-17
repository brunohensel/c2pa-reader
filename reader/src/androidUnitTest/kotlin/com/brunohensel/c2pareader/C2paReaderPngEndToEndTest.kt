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
 * End-to-end golden-file test for the PNG read path. Loads a real C2PA-tagged PNG (a ChatGPT
 * render), runs it through the full pipeline (PngAssetReader → JumbfParser → CborDecoder →
 * ManifestJsonBuilder), then compares the emitted JSON against a committed golden after
 * stripping fields the library explicitly doesn't produce in Phase 2 (cryptographic and
 * validation fields, plus `ingredients` — ingredient resolution is out of scope for the
 * reader-only pipeline shipped so far).
 *
 * Lives in `androidUnitTest` for the same reason as the JPEG end-to-end counterpart:
 * classpath resource loading is a JVM-only idiom.
 */
class C2paReaderPngEndToEndTest {

    @Test
    fun chatgptPngMatchesGoldenAfterFilter() {
        val pngBytes = loadFixtureBytes("fixtures/chatgpt_image.png")
        val goldenText = loadFixtureText("fixtures/chatgpt_image.golden.json")

        val result = C2paReader.read(pngBytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "emitted JSON does not match filtered golden")
    }

    @Test
    fun cleanPngReturnsNoManifest() {
        // Minimal PNG: signature + IHDR + IEND. Both chunks have zero-filled payload + CRC; the
        // reader doesn't validate CRCs, so this is sufficient to exercise the "no cAuI chunk" path.
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val ihdr = uint32Be(13) + "IHDR".encodeToByteArray() + ByteArray(13) + ByteArray(4)
        val iend = uint32Be(0) + "IEND".encodeToByteArray() + ByteArray(4)
        val clean = signature + ihdr + iend

        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun truncatedChatgptPngReturnsMalformed() {
        val pngBytes = loadFixtureBytes("fixtures/chatgpt_image.png")
        // Slice just past the signature into the middle of a chunk, guaranteeing a declared
        // chunk length that exceeds what remains.
        val truncated = pngBytes.copyOfRange(0, 200)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result"
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun uint32Be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderPngEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderPngEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Recursively strips fields neither the library nor this phase covers. Each entry is a
     * specific gap documented against the PRD so the filter remains a source of truth for
     * "what Phase 2 doesn't yet assert":
     *
     *  - `signature_info`, `validation_state`, `validation_results`, `validation_status` —
     *    require the COSE+X.509 verification pipeline (explicitly out of PRD scope).
     *  - `ingredients` — requires URI resolution across the manifest store (deferred).
     *  - `created_assertions`, `redacted_assertions` — claim v2 lists the reader is expected
     *    to resolve into the top-level `assertions` array with a `created: true/false` flag
     *    per entry; Phase 2 passes them through raw instead, so drop on both sides.
     *  - `created` — the per-assertion boolean flag that pairs with the above; not computed yet.
     *  - `claim_generator_info` — the CBOR sometimes encodes this as a single object and the
     *    reader-schema always wraps it in a one-element array. Normalization is a follow-up;
     *    drop on both sides for now.
     *
     * Assertions whose label starts with `c2pa.ingredient.` are also pruned: the reader is
     * supposed to resolve those into `ingredients` (already filtered), so keeping them in the
     * emitted `assertions` array creates a spurious diff against the oracle.
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
                                    label is kotlinx.serialization.json.JsonPrimitive &&
                                        label.content.startsWith("c2pa.ingredient.")
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
