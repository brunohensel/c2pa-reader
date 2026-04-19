package com.brunohensel.c2pareader

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
 * End-to-end test for the SVG read path (`SvgAssetReader` → `JumbfParser` → `CborDecoder` →
 * `ManifestJsonBuilder`).
 *
 * The fixture is synthesized at runtime by base64-encoding `chatgpt_image.c2pa` into a
 * `<c2pa:manifest>` element at the path `svg > metadata > c2pa:manifest`, matching the exact
 * structure c2pa-rs's svg_io.rs reads and writes. Because the embedded JUMBF bytes are the
 * same C2PA-signed manifest store used by the PNG and sidecar end-to-end tests, the emitted
 * JSON must match the shared `chatgpt_image.golden.json` oracle.
 */
@OptIn(ExperimentalEncodingApi::class)
class C2paReaderSvgEndToEndTest {

    @Test
    fun syntheticSvgWrappedSidecarMatchesSharedGolden() {
        val sidecar = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        val goldenText = loadFixtureText("fixtures/chatgpt_image.golden.json")

        val svg = svgWrapping(sidecar).encodeToByteArray()
        val result = C2paReader.read(svg)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "SVG-wrapped JSON does not match shared golden")
    }

    @Test
    fun cleanSvgReturnsNoManifest() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>"""
            .encodeToByteArray()
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(svg))
    }

    @Test
    fun truncatedSvgManifestReturnsMalformed() {
        val sidecar = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        val encoded = Base64.encode(sidecar)
        // Drop the `</c2pa:manifest>` close tag — the reader must surface that as Malformed.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">$encoded
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        val result = C2paReader.read(svg)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun svgWrapping(jumbf: ByteArray): String {
        val encoded = Base64.encode(jumbf)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="1" height="1">
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">
                  $encoded
                </c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent()
    }

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderSvgEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderSvgEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter rationale as [C2paReaderSidecarEndToEndTest] — the SVG wrapper ultimately
     * decodes into the identical reader-schema output as the sidecar, so the same fields must
     * be pruned from both sides for the Phase 2 slice.
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
                                    label is JsonPrimitive &&
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
