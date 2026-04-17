package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JpegAssetReaderTest {

    @Test
    fun cleanJpegWithNoApp11ReturnsNull() {
        // SOI + APP0 (JFIF placeholder) + EOI — a minimal file with zero manifest content.
        val jpeg = SOI + app0Jfif() + EOI
        assertNull(JpegAssetReader.extractJumbf(jpeg))
    }

    @Test
    fun singleApp11FragmentIsExtracted() {
        // LBox = 16 (arbitrary), TBox = "jumb" — these are the bytes the reassembler must
        // keep at the front of the output. The chunk after them is the actual JUMBF body.
        val lboxTbox = byteArrayOf(0x00, 0x00, 0x00, 0x10, 0x6A, 0x75, 0x6D, 0x62)
        val chunk = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        val jpeg = SOI + app11Fragment(en = 1, z = 1, lboxTbox = lboxTbox, chunk = chunk) + EOI

        // Reassembled output = LBox+TBox (from Z=1) + chunk.
        assertContentEquals(lboxTbox + chunk, JpegAssetReader.extractJumbf(jpeg))
    }

    @Test
    fun multipleFragmentsAreReassembledInPacketOrder() {
        val lboxTbox = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x6A, 0x75, 0x6D, 0x62) // LBox=32, TBox="jumb"
        val chunkA = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val chunkB = byteArrayOf(0xCC.toByte(), 0xDD.toByte())
        val chunkC = byteArrayOf(0xEE.toByte(), 0xFF.toByte())

        // Wire order 2, 1, 3 — reassembly sorts by Z. Every fragment carries the redundant
        // LBox+TBox header; the reassembler keeps it only from Z=1.
        val jpeg = SOI +
            app11Fragment(en = 7, z = 2, lboxTbox = lboxTbox, chunk = chunkB) +
            app11Fragment(en = 7, z = 1, lboxTbox = lboxTbox, chunk = chunkA) +
            app11Fragment(en = 7, z = 3, lboxTbox = lboxTbox, chunk = chunkC) +
            EOI

        val expected = lboxTbox + chunkA + chunkB + chunkC
        assertContentEquals(expected, JpegAssetReader.extractJumbf(jpeg))
    }

    @Test
    fun app11WithNonJpIdentifierIsIgnored() {
        // APP11 with CI = "XT" (JPEG XT tonemapping) is common in the wild and unrelated to C2PA.
        val manifestPayload = byteArrayOf(0x01, 0x02, 0x03)
        val jpeg = SOI +
            app11WithCustomCi(ci = byteArrayOf(0x58, 0x54) /* "XT" */, payload = manifestPayload) +
            EOI

        assertNull(JpegAssetReader.extractJumbf(jpeg))
    }

    @Test
    fun notAJpegThrowsMalformed() {
        val notJpeg = byteArrayOf(0x50, 0x4E, 0x47, 0x0D) // PNG signature prefix
        val exception = assertFailsWith<MalformedAssetException> {
            JpegAssetReader.extractJumbf(notJpeg)
        }
        check(exception.reason.contains("SOI")) {
            "expected SOI-related reason, got: ${exception.reason}"
        }
    }

    @Test
    fun app11WithTruncatedLengthThrowsMalformed() {
        // Declare segment length = 200 but supply only a handful of bytes.
        val jpeg = SOI + byteArrayOf(
            0xFF.toByte(), 0xEB.toByte(), // APP11 marker
            0x00.toByte(), 0xC8.toByte(), // length = 200 (well beyond what follows)
            0x4A.toByte(), 0x50.toByte(), // CI = "JP"
        )

        val exception = assertFailsWith<MalformedAssetException> {
            JpegAssetReader.extractJumbf(jpeg)
        }
        check(exception.reason.contains("exceeds")) {
            "expected truncation-related reason, got: ${exception.reason}"
        }
    }

    // --- Synthetic JPEG builders -----------------------------------------------------------------

    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** Minimal APP0 JFIF segment — gives tests a realistic pre-APP11 header to skip. */
    private fun app0Jfif(): ByteArray {
        val payload = byteArrayOf(
            0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
            0x01, 0x01, // version
            0x00,       // aspect ratio units
            0x00, 0x01, // x density
            0x00, 0x01, // y density
            0x00, 0x00, // thumbnail 0x0
        )
        val length = 2 + payload.size
        return byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + u16BE(length) + payload
    }

    /**
     * Build an APP11 "JP" fragment. Per ISO 19566-5, every fragment carries both the
     * per-fragment `CI + En + Z` preamble and a redundant copy of the JUMBF superbox's
     * 8-byte `LBox + TBox` header. Layout: `FFEB <len> "JP" <En:2> <Z:4> <LBox:4> <TBox:4> <chunk>`.
     */
    private fun app11Fragment(en: Int, z: Long, lboxTbox: ByteArray, chunk: ByteArray): ByteArray {
        require(lboxTbox.size == 8) { "LBox+TBox must be 8 bytes" }
        val content = byteArrayOf(0x4A.toByte(), 0x50.toByte()) + // CI = "JP"
            u16BE(en) +
            u32BE(z) +
            lboxTbox +
            chunk
        val segmentLength = 2 + content.size // length field includes itself
        return byteArrayOf(0xFF.toByte(), 0xEB.toByte()) + u16BE(segmentLength) + content
    }

    /** Build an APP11 segment with a custom 2-byte CI (not "JP"). */
    private fun app11WithCustomCi(ci: ByteArray, payload: ByteArray): ByteArray {
        val segmentLength = 2 + ci.size + payload.size
        return byteArrayOf(0xFF.toByte(), 0xEB.toByte()) + u16BE(segmentLength) + ci + payload
    }

    private fun u16BE(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun u32BE(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
