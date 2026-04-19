package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RiffAssetReaderTest {

    @Test
    fun webpWithC2paChunkReturnsPayload() {
        // RIFF layout: "RIFF"(4) + fileSize(4,LE) + formType(4) + chunks…
        // Each chunk: fourCC(4) + chunkSize(4,LE) + payload (+ pad byte if size is odd).
        // The reader returns the C2PA chunk payload verbatim.
        val manifest = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val webp = buildRiff(
            formType = "WEBP",
            chunks = listOf(
                chunk("VP8X", ByteArray(10)),
                chunk("C2PA", manifest),
                chunk("VP8 ", ByteArray(12)),
            ),
        )
        assertContentEquals(manifest, RiffAssetReader.extractJumbf(webp))
    }

    @Test
    fun cleanWebpReturnsNull() {
        // No C2PA chunk → well-formed but carries no manifest.
        val webp = buildRiff(
            formType = "WEBP",
            chunks = listOf(chunk("VP8X", ByteArray(10)), chunk("VP8 ", ByteArray(12))),
        )
        assertNull(RiffAssetReader.extractJumbf(webp))
    }

    @Test
    fun oddLengthChunkPadByteIsHandled() {
        // RIFF spec: chunks whose declared size is odd are followed by a single zero pad byte
        // that is NOT counted in the size. The walker must step `size + (size and 1)` bytes.
        // Put an odd-sized chunk before the C2PA chunk; if the pad is mishandled, the C2PA
        // chunk will start at the wrong offset and the test fails.
        val manifest = byteArrayOf(0x01, 0x02, 0x03)
        val webp = buildRiff(
            formType = "WEBP",
            chunks = listOf(
                chunk("VP8X", byteArrayOf(0x10, 0x20, 0x30)), // 3 bytes → 1 pad byte
                chunk("C2PA", manifest),
            ),
        )
        assertContentEquals(manifest, RiffAssetReader.extractJumbf(webp))
    }

    @Test
    fun declaredFileSizeExceedingBufferThrowsMalformed() {
        // RIFF declares 100 bytes of payload (so 108 total), but the buffer is only 24 bytes.
        // The declared-vs-buffer mismatch is surfaced at the RIFF header, before any chunk walk.
        val header = "RIFF".encodeToByteArray() + u32LE(100) + "WEBP".encodeToByteArray()
        val bogus = "C2PA".encodeToByteArray() + u32LE(1000) + byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val webp = header + bogus
        assertFailsWith<MalformedAssetException> { RiffAssetReader.extractJumbf(webp) }
    }

    @Test
    fun innerChunkExceedingDeclaredRiffEndThrowsMalformed() {
        // RIFF fileSize itself is consistent with the buffer, but an inner chunk declares a
        // payload that overruns the declared RIFF end. Caught by the chunk walker, not the
        // header-level early-throw.
        val chunks = "C2PA".encodeToByteArray() + u32LE(1000) + byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val body = "WEBP".encodeToByteArray() + chunks
        val webp = "RIFF".encodeToByteArray() + u32LE(body.size) + body
        assertFailsWith<MalformedAssetException> { RiffAssetReader.extractJumbf(webp) }
    }

    @Test
    fun trailingBytesPastDeclaredFileSizeAreIgnored() {
        // Some WebP writers pad files to a disk-alignment boundary; bytes past the declared
        // RIFF end are outside the container and must not look like malformed chunk headers.
        // Walker must stop at `8 + fileSize` and still locate the C2PA chunk in front of it.
        val manifest = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val webp = buildRiff(
            formType = "WEBP",
            chunks = listOf(chunk("VP8X", ByteArray(10)), chunk("C2PA", manifest)),
        )
        // 16 bytes of trailing zero padding, outside the RIFF.
        val padded = webp + ByteArray(16)
        assertContentEquals(manifest, RiffAssetReader.extractJumbf(padded))
    }

    @Test
    fun missingRiffHeaderThrowsMalformed() {
        // Not RIFF at all.
        val bytes = "NOPE".encodeToByteArray() + ByteArray(8)
        assertFailsWith<MalformedAssetException> { RiffAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun nonWebpFormTypeThrowsMalformed() {
        // Detector normally screens these out; this guards the reader if called directly.
        val bytes = "RIFF".encodeToByteArray() + u32LE(4) + "WAVE".encodeToByteArray()
        assertFailsWith<MalformedAssetException> { RiffAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun multipleC2paChunksReturnsFirst() {
        // Spec allows at most one; real producers obey that. If a malformed file has two,
        // prefer the first (matches the c2pa-rs riff_io.rs walker).
        val first = byteArrayOf(0x01, 0x02, 0x03)
        val second = byteArrayOf(0x09, 0x08, 0x07)
        val webp = buildRiff(
            formType = "WEBP",
            chunks = listOf(chunk("C2PA", first), chunk("C2PA", second)),
        )
        assertContentEquals(first, RiffAssetReader.extractJumbf(webp))
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun buildRiff(formType: String, chunks: List<ByteArray>): ByteArray {
        require(formType.length == 4)
        val body = formType.encodeToByteArray() + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return "RIFF".encodeToByteArray() + u32LE(body.size) + body
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        require(type.length == 4)
        val header = type.encodeToByteArray() + u32LE(data.size)
        return if (data.size % 2 == 1) header + data + byteArrayOf(0x00) else header + data
    }

    private fun u32LE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
}
