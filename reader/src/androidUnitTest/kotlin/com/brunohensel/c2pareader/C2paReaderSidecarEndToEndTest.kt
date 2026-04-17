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
 * End-to-end test for the `.c2pa` sidecar path. The fixture is the JUMBF manifest store
 * extracted from `chatgpt_image.png` (the caBX chunk payload), so the emitted JSON must match
 * the PNG golden byte-for-byte after the same filter — same manifest, same reader-schema output,
 * different delivery container.
 *
 * Lives in `androidUnitTest` because classpath resource loading is a JVM-only idiom (matches
 * the JPEG and PNG end-to-end counterparts).
 */
class C2paReaderSidecarEndToEndTest {

    @Test
    fun chatgptSidecarMatchesSameGoldenAsSourcePng() {
        val sidecarBytes = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        val goldenText = loadFixtureText("fixtures/chatgpt_image.golden.json")

        val result = C2paReader.read(sidecarBytes)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "sidecar JSON does not match PNG golden")
    }

    @Test
    fun truncatedSidecarReturnsJumbfError() {
        val sidecarBytes = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        // Cut the sidecar in the middle of a box payload. There's no image-container layer for
        // the sidecar reader to inspect, so structural damage surfaces as JumbfError — never
        // Malformed, which is reserved for container-above-JUMBF corruption.
        val truncated = sidecarBytes.copyOfRange(0, 200)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.JumbfError,
            "expected JumbfError, got $result"
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderSidecarEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderSidecarEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter rationale as [C2paReaderPngEndToEndTest.filterUnsupportedFields] — the
     * sidecar and the PNG decode into the identical reader-schema output, so the identical
     * set of fields needs pruning for the Phase 2 slice.
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
