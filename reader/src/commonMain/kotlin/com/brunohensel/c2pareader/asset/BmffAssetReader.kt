package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a still-image ISOBMFF asset (HEIF / HEIC /
 * AVIF) by walking top-level boxes, locating the C2PA `uuid` box (extended type
 * `D8FE C3D6 1B0E 483C 9297 5828 877E C481`), and returning the embedded JUMBF payload that
 * follows the C2PA purpose header.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/bmff_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/bmff_io.rs).
 * In particular, the UUID constant ([C2PA_UUID]) and the purpose-prefixed payload layout come
 * from `c2pa_boxes_from_tree_and_map` and `get_uuid_box_purpose` in that file.
 *
 * ## ISOBMFF box header (ISO/IEC 14496-12 §4.2)
 *
 * ```
 *  0       4       8          16
 * +-------+-------+------------+----------------+
 * | size  | type  | largesize  |    payload     |
 * +-------+-------+------------+----------------+
 *  4B,BE   4B      8B, BE       size - header
 *           "uuid"  (only if size == 1)
 * ```
 *
 * - `size` (4 bytes, big-endian): total box length including the size and type fields.
 *   - `size == 0` means "run to end of container"; only legal on the outermost box.
 *   - `size == 1` means the real length is the 8-byte `largesize` that follows the type.
 *   - `2 <= size < 8` is invalid (the header alone is 8 bytes).
 * - `type` (4 bytes): ASCII four-character box type (`ftyp`, `uuid`, `mdat`, …).
 * - For `type == "uuid"` a 16-byte `extended_type` (UUID) follows the header; that's how C2PA
 *   claims its box.
 *
 * ## C2PA `uuid` box payload layout
 *
 * After the 16-byte UUID, the payload is a FullBox:
 *
 * ```
 * UUID (16) | version (1) | flags (3) | purpose (UTF-8, null-terminated) | …
 * ```
 *
 * For `purpose == "manifest"` (and the equivalent `"original"` / `"update"` variants), the
 * payload continues with an 8-byte big-endian `first_aux_uuid_offset` followed by the JUMBF
 * manifest-store bytes — which is exactly what this reader returns. `purpose == "merkle"` is
 * content-hash aux data and is ignored here.
 *
 * Only the top-level box list is walked; C2PA producers place the manifest uuid box at the top
 * level of the file, matching `bmff_map.get("/uuid")` in the reference implementation.
 */
internal object BmffAssetReader : AssetReader {

    // ISO/IEC 14496-12: FourCC of the user-type box whose extended_type carries C2PA UUIDs.
    private const val TYPE_UUID: String = "uuid"

    // c2pa-rs bmff_io.rs §C2PA_UUID: the content provenance box's extended_type.
    private val C2PA_UUID: ByteArray = byteArrayOf(
        0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(),
        0x1B.toByte(), 0x0E.toByte(), 0x48.toByte(), 0x3C.toByte(),
        0x92.toByte(), 0x97.toByte(), 0x58.toByte(), 0x28.toByte(),
        0x87.toByte(), 0x7E.toByte(), 0xC4.toByte(), 0x81.toByte(),
    )

    // Purposes that carry a JUMBF manifest store (c2pa-rs bmff_io.rs: MANIFEST / ORIGINAL / UPDATE).
    private val MANIFEST_PURPOSES: Set<String> = setOf("manifest", "original", "update")

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        var pos = 0
        while (pos < bytes.size) {
            val header = readBoxHeader(bytes, pos)
            val payloadStart = header.payloadStart
            val boxEnd = header.boxEnd
            if (header.type == TYPE_UUID) {
                val jumbf = tryExtractC2paJumbf(bytes, payloadStart, boxEnd)
                if (jumbf != null) return jumbf
            }
            pos = boxEnd
        }
        return null
    }

    /**
     * Inspects a `uuid` box and returns its JUMBF payload if (a) the extended_type matches the
     * C2PA UUID, (b) the FullBox `purpose` string is one of [MANIFEST_PURPOSES], and (c) the
     * 8-byte `first_aux_uuid_offset` can be skipped. Returns null for any other uuid box
     * (including C2PA boxes carrying a non-manifest purpose).
     */
    private fun tryExtractC2paJumbf(bytes: ByteArray, payloadStart: Int, boxEnd: Int): ByteArray? {
        // Extended UUID occupies the first 16 bytes of the uuid box payload.
        if (boxEnd - payloadStart < 16) {
            throw MalformedAssetException("uuid box payload too short for extended type")
        }
        for (i in C2PA_UUID.indices) {
            if (bytes[payloadStart + i] != C2PA_UUID[i]) return null
        }

        // FullBox header: version(1) + flags(3) = 4 bytes. ISO/IEC 14496-12 §4.2.2.
        val versionFlagsStart = payloadStart + 16
        val purposeStart = versionFlagsStart + 4
        if (purposeStart > boxEnd) {
            throw MalformedAssetException("C2PA uuid box truncated before FullBox header")
        }

        // Purpose is a null-terminated UTF-8 string.
        var nul = purposeStart
        while (nul < boxEnd && bytes[nul] != 0x00.toByte()) nul++
        if (nul >= boxEnd) {
            throw MalformedAssetException("C2PA uuid box purpose is not null-terminated")
        }
        val purpose = bytes.copyOfRange(purposeStart, nul).decodeToString()
        if (purpose !in MANIFEST_PURPOSES) return null

        // For manifest/original/update, an 8-byte big-endian first_aux_uuid_offset follows the
        // purpose's null terminator; the JUMBF manifest store occupies the rest of the box.
        val jumbfStart = nul + 1 + 8
        if (jumbfStart > boxEnd) {
            throw MalformedAssetException("C2PA uuid box truncated before JUMBF payload")
        }
        return bytes.copyOfRange(jumbfStart, boxEnd)
    }

    private data class BoxHeader(val type: String, val payloadStart: Int, val boxEnd: Int)

    private fun readBoxHeader(bytes: ByteArray, pos: Int): BoxHeader {
        if (pos + 8 > bytes.size) {
            throw MalformedAssetException("truncated BMFF box header at offset $pos")
        }
        val size32 = readUint32BE(bytes, pos)
        val type = readFourCc(bytes, pos + 4)

        val (totalSize: Long, headerSize: Int) = when (size32) {
            // size == 0: runs to end of file (only legal outermost; treat as such).
            0L -> (bytes.size - pos).toLong() to 8
            // size == 1: 8-byte extended largesize follows.
            1L -> {
                if (pos + 16 > bytes.size) {
                    throw MalformedAssetException("truncated BMFF large-box header at offset $pos")
                }
                val largesize = readUint64BE(bytes, pos + 8)
                if (largesize < 16L) {
                    throw MalformedAssetException("invalid BMFF largesize $largesize at offset $pos")
                }
                largesize to 16
            }
            else -> {
                if (size32 < 8L) {
                    throw MalformedAssetException("invalid BMFF box size $size32 at offset $pos")
                }
                size32 to 8
            }
        }
        val boxEndLong = pos.toLong() + totalSize
        if (boxEndLong > bytes.size) {
            throw MalformedAssetException(
                "BMFF box at offset $pos claims size $totalSize, exceeds remaining bytes"
            )
        }
        return BoxHeader(type = type, payloadStart = pos + headerSize, boxEnd = boxEndLong.toInt())
    }

    private fun readUint32BE(bytes: ByteArray, pos: Int): Long =
        ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)

    private fun readUint64BE(bytes: ByteArray, pos: Int): Long {
        // Negative values would wrap to > Long.MAX_VALUE unsigned — reject them, since any
        // BMFF file that large is nonsense for our ByteArray-only API.
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        }
        if (v < 0L) throw MalformedAssetException("BMFF largesize overflows Long at offset $pos")
        return v
    }

    private fun readFourCc(bytes: ByteArray, pos: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[pos + i].toInt() and 0xFF).toChar())
    }
}
