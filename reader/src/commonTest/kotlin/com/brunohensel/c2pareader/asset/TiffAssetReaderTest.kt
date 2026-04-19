package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TiffAssetReaderTest {

    @Test
    fun littleEndianTiffWithC2paTagAtExternalOffsetReturnsPayload() {
        // TIFF 6.0 §2: header = byteOrder(2) + magic(2) + firstIfdOffset(4). IFD = count(2) +
        // N entries (each 12 B: tag(2) + type(2) + count(4) + valueOrOffset(4)) + nextIfd(4).
        // C2PA_TAG = 0xCD41, field type UNDEFINED (7). Payload larger than 4 bytes lives at the
        // offset encoded in valueOrOffset.
        val manifest = "JUMBFJUMBFJUMBF!".encodeToByteArray() // 16 bytes — external
        val tiff = buildLittleEndianTiff(
            entries = listOf(ifdEntry(tag = 0xCD41, type = 7, count = manifest.size, inlineOrOffset = null)),
            externalPayload = manifest,
        )
        assertContentEquals(manifest, TiffAssetReader.extractJumbf(tiff))
    }

    @Test
    fun bigEndianTiffWithC2paTagAtExternalOffsetReturnsPayload() {
        val manifest = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val tiff = buildBigEndianTiff(
            entries = listOf(ifdEntry(tag = 0xCD41, type = 7, count = manifest.size, inlineOrOffset = null)),
            externalPayload = manifest,
        )
        assertContentEquals(manifest, TiffAssetReader.extractJumbf(tiff))
    }

    @Test
    fun cleanTiffWithoutC2paTagReturnsNull() {
        // A tag the reader does not care about (tag 0x0100 = ImageWidth), payload inlined in
        // valueOrOffset. No C2PA_TAG present → return null.
        val tiff = buildLittleEndianTiff(
            entries = listOf(
                ifdEntry(tag = 0x0100, type = 3 /*SHORT*/, count = 1, inlineOrOffset = 0x00000040),
            ),
            externalPayload = ByteArray(0),
        )
        assertNull(TiffAssetReader.extractJumbf(tiff))
    }

    @Test
    fun truncatedIfdThrowsMalformed() {
        // Header points to an IFD offset that is past EOF.
        val bytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // "II" + magic 42
            0x08, 0x00, 0x00, 0x00, // firstIfdOffset = 8
            // buffer ends here — no IFD at offset 8
        )
        assertFailsWith<MalformedAssetException> { TiffAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun c2paTagWithValueOffsetPastEofThrowsMalformed() {
        // C2PA_TAG declares a valid external offset, but pointing past EOF.
        val tiff = buildLittleEndianTiff(
            entries = listOf(
                ifdEntry(
                    tag = 0xCD41,
                    type = 7,
                    count = 1000, // far more than the buffer can hold
                    inlineOrOffset = 0x0000FFFF.toLong().toInt(),
                ),
            ),
            externalPayload = ByteArray(0),
        )
        assertFailsWith<MalformedAssetException> { TiffAssetReader.extractJumbf(tiff) }
    }

    @Test
    fun wrongMagicThrowsMalformed() {
        // "II" byte-order marker but magic number is not 42 — header is structurally bad.
        val bytes = byteArrayOf(0x49, 0x49, 0x2B, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertFailsWith<MalformedAssetException> { TiffAssetReader.extractJumbf(bytes) }
    }

    @Test
    fun c2paTagWithWrongFieldTypeThrowsMalformed() {
        // C2PA spec requires field type UNDEFINED (7). Any other type is structurally invalid.
        val manifest = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val tiff = buildLittleEndianTiff(
            entries = listOf(
                ifdEntry(tag = 0xCD41, type = 1 /*BYTE — wrong for C2PA*/, count = manifest.size, inlineOrOffset = null),
            ),
            externalPayload = manifest,
        )
        assertFailsWith<MalformedAssetException> { TiffAssetReader.extractJumbf(tiff) }
    }

    // --- Synthetic TIFF builders ---------------------------------------------------------------

    /**
     * Build a single-IFD LE TIFF. `externalPayload` is appended after the IFD and every entry
     * with `inlineOrOffset == null` gets its `valueOrOffset` field set to point at the start of
     * that payload (simple, since tests only add one external payload per file).
     */
    private fun buildLittleEndianTiff(entries: List<IfdEntryBuilder>, externalPayload: ByteArray): ByteArray =
        buildTiff(littleEndian = true, entries = entries, externalPayload = externalPayload)

    private fun buildBigEndianTiff(entries: List<IfdEntryBuilder>, externalPayload: ByteArray): ByteArray =
        buildTiff(littleEndian = false, entries = entries, externalPayload = externalPayload)

    private fun buildTiff(
        littleEndian: Boolean,
        entries: List<IfdEntryBuilder>,
        externalPayload: ByteArray,
    ): ByteArray {
        val header = if (littleEndian) {
            byteArrayOf(0x49, 0x49, 0x2A, 0x00) + u32(8, littleEndian)
        } else {
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A) + u32(8, littleEndian)
        }
        val ifdSize = 2 + entries.size * 12 + 4 // count + entries + nextIfdOffset
        val externalOffset = 8 + ifdSize
        val ifd = u16(entries.size, littleEndian) +
            entries.fold(ByteArray(0)) { acc, e ->
                val v = e.inlineOrOffset ?: externalOffset
                acc + u16(e.tag, littleEndian) + u16(e.type, littleEndian) +
                    u32(e.count, littleEndian) + u32(v, littleEndian)
            } +
            u32(0, littleEndian) // nextIfdOffset = 0 → last IFD
        return header + ifd + externalPayload
    }

    private fun ifdEntry(tag: Int, type: Int, count: Int, inlineOrOffset: Int?): IfdEntryBuilder =
        IfdEntryBuilder(tag, type, count, inlineOrOffset)

    private data class IfdEntryBuilder(val tag: Int, val type: Int, val count: Int, val inlineOrOffset: Int?)

    private fun u16(v: Int, littleEndian: Boolean): ByteArray =
        if (littleEndian) {
            byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        } else {
            byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        }

    private fun u32(v: Int, littleEndian: Boolean): ByteArray =
        if (littleEndian) {
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 24) and 0xFF).toByte(),
            )
        } else {
            byteArrayOf(
                ((v ushr 24) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                (v and 0xFF).toByte(),
            )
        }
}
