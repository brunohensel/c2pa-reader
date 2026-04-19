package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JpegXlAssetReaderTest {

    @Test
    fun containerWithC2paJumbBoxReturnsFullBoxBytes() {
        // Build a JXL container whose first non-signature top-level box is a `jumb` labeled "c2pa".
        // Because JUMBF boxes and BMFF boxes share the same LBox+TBox header, the reader must
        // return the full `jumb` box verbatim — that is itself a valid JUMBF byte stream.
        val manifestChild = box("cbor", byteArrayOf(0x01, 0x02, 0x03))
        val jumb = jumbBox(label = "c2pa", children = listOf(manifestChild))
        val bytes = JXL_SIG + dummyFtyp() + jumb
        assertContentEquals(jumb, JpegXlAssetReader.extractJumbf(bytes))
    }

    @Test
    fun containerWithOnlyNonC2paJumbReturnsNull() {
        // A `jumb` box labeled something other than "c2pa" (e.g. EXIF metadata) must be skipped.
        val exifChild = box("cbor", byteArrayOf(0xAA.toByte()))
        val exifJumb = jumbBox(label = "exif", children = listOf(exifChild))
        val bytes = JXL_SIG + dummyFtyp() + exifJumb
        assertNull(JpegXlAssetReader.extractJumbf(bytes))
    }

    @Test
    fun containerWithMultipleJumbsReturnsTheC2paOne() {
        // EXIF jumb appears before C2PA jumb — walker must keep going past non-C2PA labels.
        val exifJumb = jumbBox(label = "exif", children = listOf(box("cbor", byteArrayOf(0x10))))
        val c2paChild = box("cbor", byteArrayOf(0x20, 0x30))
        val c2paJumb = jumbBox(label = "c2pa", children = listOf(c2paChild))
        val bytes = JXL_SIG + dummyFtyp() + exifJumb + c2paJumb
        assertContentEquals(c2paJumb, JpegXlAssetReader.extractJumbf(bytes))
    }

    @Test
    fun nakedCodestreamReturnsNull() {
        // Naked codestream starts with `FF 0A`; per spec it cannot carry a C2PA manifest, so the
        // reader returns null and the orchestrator maps that to NoManifest.
        val bytes = byteArrayOf(0xFF.toByte(), 0x0A) + ByteArray(32)
        assertNull(JpegXlAssetReader.extractJumbf(bytes))
    }

    @Test
    fun nonJxlBytesThrowMalformed() {
        // Detector normally screens these out; guards the reader if called directly.
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x00)
        assertFailsWith<MalformedAssetException> { JpegXlAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun truncatedBoxHeaderThrowsMalformed() {
        // Signature present but the next box header is truncated.
        val bytes = JXL_SIG + byteArrayOf(0x00, 0x00, 0x00)
        assertFailsWith<MalformedAssetException> { JpegXlAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun declaredBoxSizeExceedsBufferThrowsMalformed() {
        // `jumb` box declares 4096 bytes but the buffer doesn't back that claim.
        val bogus = byteArrayOf(
            0x00, 0x00, 0x10, 0x00,                   // size = 4096
            0x6A, 0x75, 0x6D, 0x62,                   // "jumb"
        )
        val bytes = JXL_SIG + bogus
        assertFailsWith<MalformedAssetException> { JpegXlAssetReader.extractJumbf(bytes) }
    }

    // --- helpers -------------------------------------------------------------------------------

    // JPEG XL ISOBMFF container signature (c2pa-rs jpegxl_io.rs JXL_CONTAINER_MAGIC).
    private val JXL_SIG: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C,
        0x4A, 0x58, 0x4C, 0x20,
        0x0D, 0x0A, 0x87.toByte(), 0x0A,
    )

    /** Minimal `ftyp` box — not required for detection, but realistic for a JXL container. */
    private fun dummyFtyp(): ByteArray {
        val payload = "jxl ".encodeToByteArray() + byteArrayOf(0, 0, 0, 0)
        return box("ftyp", payload)
    }

    /** Generic BMFF/JUMBF box: size(4,BE) + type(4) + payload. */
    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        val size = 8 + payload.size
        return u32BE(size) + type.encodeToByteArray() + payload
    }

    /**
     * Build a JUMBF superbox (`jumb`) whose first child is a `jumd` description carrying the
     * given label, followed by zero or more content child boxes. Mirrors the C2PA manifest-store
     * shape but only populates what this reader actually inspects (label) — children are opaque.
     */
    private fun jumbBox(label: String, children: List<ByteArray>): ByteArray {
        val jumdPayload =
            ByteArray(16) +                                           // contentTypeUUID (zero'd)
                byteArrayOf(0x02) +                                    // toggle: LABEL_PRESENT
                label.encodeToByteArray() + byteArrayOf(0x00)          // null-terminated label
        val jumd = box("jumd", jumdPayload)
        val body = children.fold(jumd) { acc, c -> acc + c }
        return box("jumb", body)
    }

    private fun u32BE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )
}
