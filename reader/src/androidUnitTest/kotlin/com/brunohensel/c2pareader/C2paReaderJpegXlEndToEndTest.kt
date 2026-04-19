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
 * End-to-end test for the JPEG XL read path (`JpegXlAssetReader` → `JumbfParser` →
 * `CborDecoder` → `ManifestJsonBuilder`).
 *
 * The fixture is synthesized at runtime by wrapping `chatgpt_image.c2pa` (a real C2PA-signed
 * manifest store from the Phase-1 PNG pipeline) inside a JPEG XL container. Because the
 * sidecar bytes ARE a full JUMBF `jumb` superbox, and because BMFF box headers and JUMBF box
 * headers share identical layout, concatenating `JXL_CONTAINER_MAGIC` + `ftyp` + sidecar bytes
 * produces a valid JXL container whose extracted JUMBF store is byte-identical to the sidecar.
 *
 * The oracle is therefore the same `chatgpt_image.golden.json` consumed by the PNG and sidecar
 * end-to-end tests — a JXL-specific oracle would require a C2PA-signed real-world JXL asset,
 * which the reference toolchain doesn't publish in fixtures at the time of writing.
 */
class C2paReaderJpegXlEndToEndTest {

    @Test
    fun syntheticJxlWrappedSidecarMatchesSharedGolden() {
        val sidecar = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        val goldenText = loadFixtureText("fixtures/chatgpt_image.golden.json")

        val jxl = JXL_CONTAINER_MAGIC + ftypBox("jxl ") + sidecar
        val result = C2paReader.read(jxl)
        val success = result as? C2paResult.Success
            ?: fail("expected Success, got $result")

        val emitted = Json.parseToJsonElement(success.json).filterUnsupportedFields()
        val golden = Json.parseToJsonElement(goldenText).filterUnsupportedFields()

        assertEquals(golden, emitted, "JXL-wrapped JSON does not match shared golden")
    }

    @Test
    fun cleanJxlContainerReturnsNoManifest() {
        // JXL signature + `ftyp` box only — no `jumb` box anywhere. Expected: NoManifest, not
        // Malformed (the container is structurally well-formed, just carries no C2PA data).
        val jxl = JXL_CONTAINER_MAGIC + ftypBox("jxl ")
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(jxl))
    }

    @Test
    fun nakedCodestreamReturnsNoManifest() {
        // JXL codestream (`FF 0A` + arbitrary data) cannot carry a C2PA manifest by spec.
        val codestream = byteArrayOf(0xFF.toByte(), 0x0A) + ByteArray(64)
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(codestream))
    }

    @Test
    fun truncatedJxlContainerReturnsMalformed() {
        val sidecar = loadFixtureBytes("fixtures/chatgpt_image.c2pa")
        val jxl = JXL_CONTAINER_MAGIC + ftypBox("jxl ") + sidecar
        // Cut the container in the middle of a box payload — declared box size now overruns
        // the buffer, which the reader must surface as Malformed before the JUMBF layer runs.
        val truncated = jxl.copyOfRange(0, 60)
        val result = C2paReader.read(truncated)
        assertTrue(
            result is C2paResult.Failure && result.error is C2paError.Malformed,
            "expected Malformed, got $result",
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    // JPEG XL ISOBMFF container signature (c2pa-rs jpegxl_io.rs JXL_CONTAINER_MAGIC).
    private val JXL_CONTAINER_MAGIC: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C,
        0x4A, 0x58, 0x4C, 0x20,
        0x0D, 0x0A, 0x87.toByte(), 0x0A,
    )

    private fun ftypBox(majorBrand: String): ByteArray {
        require(majorBrand.length == 4)
        val payload = majorBrand.encodeToByteArray() + byteArrayOf(0, 0, 0, 0)
        val size = 8 + payload.size
        return byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        ) + "ftyp".encodeToByteArray() + payload
    }

    private fun loadFixtureBytes(path: String): ByteArray {
        val stream = C2paReaderJpegXlEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes() }
    }

    private fun loadFixtureText(path: String): String {
        val stream = C2paReaderJpegXlEndToEndTest::class.java.classLoader!!.getResourceAsStream(path)
            ?: fail("fixture '$path' not found on test classpath")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * Same filter rationale as [C2paReaderSidecarEndToEndTest] — the JXL wrapper ultimately
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
