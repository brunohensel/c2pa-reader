package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a JPEG XL asset.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/jpegxl_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/jpegxl_io.rs).
 * `JXL_CONTAINER_MAGIC`, the `jumb` box type, and the "c2pa" label check all come from that file.
 *
 * ## JXL container (ISO/IEC 18181-2)
 *
 * A JXL ISOBMFF container begins with a fixed 12-byte signature box — the same
 * `size(4,BE) + type("JXL ") + payload(0D 0A 87 0A)` layout as the PNG signature, but wrapped
 * in a BMFF box so the parser can recognize the file before reading any variable data:
 *
 * ```
 * 00 00 00 0C 4A 58 4C 20 0D 0A 87 0A   ← signature box
 * <subsequent top-level boxes>
 * ```
 *
 * C2PA piggy-backs on the general JUMBF-in-BMFF transport: the manifest store lives in a
 * top-level `jumb` box whose first child `jumd` carries the label "c2pa". Because JUMBF boxes
 * and BMFF boxes share the same `size(4,BE) + type(4)` header layout, the bytes of a top-level
 * `jumb` BMFF box ARE a valid JUMBF byte stream — we return them verbatim and [JumbfParser]
 * takes over from there.
 *
 * ## Naked codestream
 *
 * A JXL codestream (`FF 0A` sync marker) has no box layer and cannot carry a manifest store.
 * Returning `null` is the right behaviour for a read-only library — "no provenance" is a
 * normal outcome, not an error — matching the `NoManifest` contract for every other format.
 *
 * ## Multiple `jumb` boxes
 *
 * JXL containers can hold multiple `jumb` boxes (e.g. EXIF in one, C2PA in another), so we
 * peek at each box's first child description to pick the one labeled `"c2pa"`. If no label
 * check is possible (box too short, no `jumd` first child), we skip to the next `jumb`.
 */
internal object JpegXlAssetReader : AssetReader {

    // c2pa-rs jpegxl_io.rs: the 12-byte signature that marks a JXL ISOBMFF container.
    private val JXL_CONTAINER_MAGIC: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C,
        0x4A, 0x58, 0x4C, 0x20,                         // "JXL "
        0x0D, 0x0A, 0x87.toByte(), 0x0A,
    )

    // JXL naked codestream sync marker (ISO/IEC 18181-1 §9.1).
    private val JXL_CODESTREAM_MAGIC: ByteArray = byteArrayOf(0xFF.toByte(), 0x0A)

    // JUMBF / BMFF FourCCs. `jumb` is the superbox carrying C2PA content; `jumd` is the
    // description box that carries the label we match on.
    private const val TYPE_JUMB: String = "jumb"
    private const val TYPE_JUMD: String = "jumd"

    // C2PA content label at the top of the JUMBF manifest store.
    private const val C2PA_LABEL: String = "c2pa"

    // Bit 1 of the jumd toggle byte: LABEL_PRESENT (ISO/IEC 19566-5 §B.6).
    private const val JUMD_TOGGLE_LABEL_PRESENT: Int = 0x02

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        when {
            startsWith(bytes, JXL_CONTAINER_MAGIC) -> {} // continue below
            startsWith(bytes, JXL_CODESTREAM_MAGIC) -> return null // codestream cannot carry C2PA
            else -> throw MalformedAssetException("not a JPEG XL file (missing signature)")
        }

        var pos = 0
        while (pos < bytes.size) {
            val header = readBoxHeader(bytes, pos)
            if (header.type == TYPE_JUMB && isC2paLabeled(bytes, header.payloadStart, header.boxEnd)) {
                return bytes.copyOfRange(pos, header.boxEnd)
            }
            pos = header.boxEnd
        }
        return null
    }

    /**
     * Peeks at the `jumb` box interior to see if its first child is a `jumd` description
     * carrying the `"c2pa"` label. Returns false for any other label or for malformed
     * interiors — malformations surface later if this turns out to be the only candidate
     * (caller returns `null`, then a truly malformed file is caught by [JumbfParser]).
     */
    private fun isC2paLabeled(bytes: ByteArray, payloadStart: Int, boxEnd: Int): Boolean {
        // Minimum viable jumd: header(8) + UUID(16) + toggle(1) + "c2pa"(4) + NUL(1) = 30 bytes.
        if (boxEnd - payloadStart < 30) return false
        val jumdHeader = readBoxHeader(bytes, payloadStart)
        if (jumdHeader.type != TYPE_JUMD) return false
        // jumd payload: contentTypeUUID(16) + toggle(1) + optional null-terminated label.
        val labelStart = jumdHeader.payloadStart + 16 + 1
        if (labelStart >= jumdHeader.boxEnd) return false
        val toggle = bytes[jumdHeader.payloadStart + 16].toInt() and 0xFF
        if (toggle and JUMD_TOGGLE_LABEL_PRESENT == 0) return false
        // Find null-terminator within the jumd box.
        var end = labelStart
        while (end < jumdHeader.boxEnd && bytes[end] != 0x00.toByte()) end++
        if (end >= jumdHeader.boxEnd) return false
        val label = bytes.copyOfRange(labelStart, end).decodeToString()
        return label == C2PA_LABEL
    }

    private data class BoxHeader(val type: String, val payloadStart: Int, val boxEnd: Int)

    /**
     * Reads an ISOBMFF box header at [pos]. JXL uses the same box format as HEIF, so this
     * mirrors the [BmffAssetReader] header walker (size 0 → rest of file, size 1 → 8-byte
     * largesize follows, any `2..7` → invalid).
     */
    private fun readBoxHeader(bytes: ByteArray, pos: Int): BoxHeader {
        if (pos + 8 > bytes.size) {
            throw MalformedAssetException("truncated JXL box header at offset $pos")
        }
        val size32 = readUint32BE(bytes, pos)
        val type = readFourCc(bytes, pos + 4)

        val (totalSize: Long, headerSize: Int) = when (size32) {
            0L -> (bytes.size - pos).toLong() to 8
            1L -> {
                if (pos + 16 > bytes.size) {
                    throw MalformedAssetException("truncated JXL large-box header at offset $pos")
                }
                val largesize = readUint64BE(bytes, pos + 8)
                if (largesize < 16L) {
                    throw MalformedAssetException("invalid JXL largesize $largesize at offset $pos")
                }
                largesize to 16
            }
            else -> {
                if (size32 < 8L) {
                    throw MalformedAssetException("invalid JXL box size $size32 at offset $pos")
                }
                size32 to 8
            }
        }
        val boxEndLong = pos.toLong() + totalSize
        if (boxEndLong > bytes.size) {
            throw MalformedAssetException(
                "JXL box at offset $pos claims size $totalSize, exceeds remaining bytes"
            )
        }
        return BoxHeader(type = type, payloadStart = pos + headerSize, boxEnd = boxEndLong.toInt())
    }

    private fun startsWith(bytes: ByteArray, sig: ByteArray): Boolean {
        if (bytes.size < sig.size) return false
        for (i in sig.indices) if (bytes[i] != sig[i]) return false
        return true
    }

    private fun readUint32BE(bytes: ByteArray, pos: Int): Long =
        ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)

    private fun readUint64BE(bytes: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        if (v < 0L) throw MalformedAssetException("JXL largesize overflows Long at offset $pos")
        return v
    }

    private fun readFourCc(bytes: ByteArray, pos: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[pos + i].toInt() and 0xFF).toChar())
    }
}
