package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PngAssetReaderTest {

    @Test
    fun taggedPngReturnsCabxPayload() {
        val manifest = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val bytes = buildPng(
            chunk("IHDR", ByteArray(13)),
            chunk("caBX", manifest),
            chunk("IEND", ByteArray(0)),
        )
        assertContentEquals(manifest, PngAssetReader.extractJumbf(bytes))
    }

    @Test
    fun cleanPngReturnsNull() {
        val bytes = buildPng(
            chunk("IHDR", ByteArray(13)),
            chunk("IEND", ByteArray(0)),
        )
        assertNull(PngAssetReader.extractJumbf(bytes))
    }

    @Test
    fun truncatedChunkLengthThrowsMalformed() {
        // caBX chunk declares a 1000-byte payload, but we only provide 4 bytes after the header.
        val header = pngSignature() +
            uint32Be(13) + "IHDR".encodeToByteArray() + ByteArray(13) + ByteArray(4) // IHDR + CRC
        val truncatedChunk = uint32Be(1000) + "caBX".encodeToByteArray() + ByteArray(4)
        val bytes = header + truncatedChunk

        assertFailsWith<MalformedAssetException> { PngAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun missingSignatureThrowsMalformed() {
        // Starts with IHDR-like bytes but no PNG signature prefix.
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        assertFailsWith<MalformedAssetException> { PngAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun multipleCabxChunksUsesFirst() {
        val first = byteArrayOf(0x01, 0x02, 0x03)
        val second = byteArrayOf(0x09, 0x08, 0x07)
        val bytes = buildPng(
            chunk("IHDR", ByteArray(13)),
            chunk("caBX", first),
            chunk("caBX", second),
            chunk("IEND", ByteArray(0)),
        )
        assertContentEquals(first, PngAssetReader.extractJumbf(bytes))
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun uint32Be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    /** Build a chunk as `length(4) + type(4) + data + crc(4)`. CRC is zeroed — the reader ignores it. */
    private fun chunk(type: String, data: ByteArray): ByteArray =
        uint32Be(data.size) + type.encodeToByteArray() + data + ByteArray(4)

    private fun buildPng(vararg chunks: ByteArray): ByteArray {
        var out = pngSignature()
        for (c in chunks) out += c
        return out
    }
}
