package com.brunohensel.c2pareader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end test for the TIFF read path (`TiffAssetReader` → `JumbfParser` → `CborDecoder`
 * → `ManifestJsonBuilder`). The fixture is `c2pa-rs/sdk/benches/fixtures/100kb-signed.tiff`,
 * the only pre-signed TIFF currently shipped in the c2pa-rs corpus. DNG is covered structurally
 * by the unit tests — no pre-signed DNG fixture is available in c2pa-rs either.
 *
 * As with the HEIF / WebP end-to-end suites, a byte-exact `c2pa-rs` `Reader::json()` golden is
 * not committed because generating one requires `c2patool` / `cargo`, which aren't available
 * here. Coverage asserts the pipeline produces reader-schema–shaped output; strict oracle
 * comparison is a follow-up.
 */
class C2paReaderTiffEndToEndTest {

    @Test
    fun taggedTiffProducesStructurallyValidReaderSchemaJson() {
        val bytes = loadFixtureBytes("fixtures/100kb-signed.tiff")
        val success = (C2paReader.read(bytes) as? C2paResult.Success)
            ?: fail("expected Success for 100kb-signed.tiff, got ${C2paReader.read(bytes)}")

        val root = Json.parseToJsonElement(success.json) as? JsonObject
            ?: fail("emitted JSON is not an object")
        val activeLabel = (root["active_manifest"] as? JsonPrimitive)?.content
        assertNotNull(activeLabel, "missing active_manifest")
        val manifests = root["manifests"] as? JsonObject ?: fail("manifests is not an object")
        assertTrue(manifests.isNotEmpty(), "manifests object is empty")
        assertTrue(activeLabel in manifests, "active_manifest '$activeLabel' not in manifests")
    }

    @Test
    fun cleanTiffReturnsNoManifest() {
        // Minimal LE TIFF: header + zero-entry IFD. No C2PA tag → NoManifest.
        val clean = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00,                   // "II" + magic 42
            0x08, 0x00, 0x00, 0x00,                   // firstIfdOffset = 8
            0x00, 0x00,                               // entryCount = 0
            0x00, 0x00, 0x00, 0x00,                   // nextIfdOffset = 0
        )
        assertEquals(C2paResult.Failure(C2paError.NoManifest), C2paReader.read(clean))
    }

    @Test
    fun truncatedTaggedTiffReturnsMalformed() {
        val bytes = loadFixtureBytes("fixtures/100kb-signed.tiff")
        // 20 bytes is enough to look like TIFF magic + IFD offset, but the IFD is truncated.
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
}
