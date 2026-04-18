package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a TIFF or DNG asset by walking the first
 * IFD for tag `0xCD41` (the C2PA content-authenticity tag) and returning its payload bytes.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/tiff_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/tiff_io.rs).
 * The tag value ([C2PA_TAG]) and required field type ([C2PA_FIELD_TYPE]) both match
 * that file.
 *
 * ## TIFF wire format (TIFF 6.0 §2)
 *
 * ```
 * byteOrder (2)  magic (2, = 42 in that order)  firstIfdOffset (4)
 * ```
 *
 * `byteOrder` is `"II"` (0x4949) for little-endian or `"MM"` (0x4D4D) for big-endian. Every
 * subsequent multi-byte field follows that byte order. DNG (Digital Negative) uses the same
 * header + IFD format — no DNG-specific logic is needed.
 *
 * An **IFD** (Image File Directory) is:
 *
 * ```
 * entryCount (2)  entries[entryCount]  nextIfdOffset (4)
 * ```
 *
 * Each IFD entry is 12 bytes: `tag(2) + type(2) + count(4) + valueOrOffset(4)`. If
 * `count * sizeof(type) <= 4`, the bytes are packed into `valueOrOffset` directly; otherwise
 * `valueOrOffset` is a byte offset from the start of the file to the external payload.
 *
 * C2PA always stores its manifest as `type == UNDEFINED (7)` with count = manifest byte size,
 * so for any non-trivial manifest the payload lives externally. This reader does not support
 * BigTIFF (c2pa-rs does, but C2PA fixtures in the wild are classic TIFF) — add that later if
 * a real BigTIFF fixture surfaces.
 *
 * Only the first IFD is walked; c2pa-rs's full reader checks both first and last pages for
 * backwards compatibility, but v1 fixtures place the tag on the first IFD.
 */
internal object TiffAssetReader : AssetReader {

    // c2pa-rs tiff_io.rs: C2PA_TAG identifies the manifest-carrying IFD entry.
    private const val C2PA_TAG: Int = 0xCD41

    // c2pa-rs tiff_io.rs: C2PA_FIELD_TYPE — payload bytes are UNDEFINED (IFDEntryType::Undefined).
    private const val C2PA_FIELD_TYPE: Int = 7

    // TIFF 6.0 §2: magic number after the byte-order marker.
    private const val TIFF_MAGIC: Int = 42

    private const val HEADER_SIZE: Int = 8   // byteOrder(2) + magic(2) + firstIfdOffset(4)
    private const val IFD_ENTRY_SIZE: Int = 12

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        if (bytes.size < HEADER_SIZE) {
            throw MalformedAssetException("buffer too short for TIFF header")
        }
        val littleEndian = when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true   // "II"
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false  // "MM"
            else -> throw MalformedAssetException("not a TIFF (invalid byte-order marker)")
        }
        val magic = readUint16(bytes, 2, littleEndian)
        if (magic != TIFF_MAGIC) {
            throw MalformedAssetException("TIFF magic $magic, expected $TIFF_MAGIC")
        }
        val firstIfdOffset = readUint32(bytes, 4, littleEndian)
        if (firstIfdOffset < HEADER_SIZE.toLong() || firstIfdOffset >= bytes.size.toLong()) {
            throw MalformedAssetException("TIFF firstIfdOffset $firstIfdOffset out of bounds")
        }

        val ifdStart = firstIfdOffset.toInt()
        if (ifdStart + 2 > bytes.size) {
            throw MalformedAssetException("truncated TIFF IFD header at offset $ifdStart")
        }
        val entryCount = readUint16(bytes, ifdStart, littleEndian)
        val entriesEnd = ifdStart + 2 + entryCount * IFD_ENTRY_SIZE
        if (entriesEnd + 4 > bytes.size) {
            throw MalformedAssetException("truncated TIFF IFD entries at offset $ifdStart")
        }

        for (i in 0 until entryCount) {
            val entryStart = ifdStart + 2 + i * IFD_ENTRY_SIZE
            val tag = readUint16(bytes, entryStart, littleEndian)
            if (tag != C2PA_TAG) continue

            val type = readUint16(bytes, entryStart + 2, littleEndian)
            if (type != C2PA_FIELD_TYPE) {
                throw MalformedAssetException(
                    "C2PA IFD entry has field type $type, expected UNDEFINED ($C2PA_FIELD_TYPE)"
                )
            }
            val countLong = readUint32(bytes, entryStart + 4, littleEndian)
            if (countLong < 0L || countLong > Int.MAX_VALUE.toLong()) {
                throw MalformedAssetException("C2PA IFD entry count $countLong out of range")
            }
            val count = countLong.toInt()
            val valueOrOffset = readUint32(bytes, entryStart + 8, littleEndian)

            // For UNDEFINED (type 7), each element is 1 byte, so `count` equals payload size.
            val payloadStart: Int
            if (count <= 4) {
                // Payload is inlined in the 4-byte valueOrOffset field itself.
                payloadStart = entryStart + 8
                return bytes.copyOfRange(payloadStart, payloadStart + count)
            }
            if (valueOrOffset < 0L || valueOrOffset > bytes.size.toLong() - count.toLong()) {
                throw MalformedAssetException(
                    "C2PA IFD entry payload at offset $valueOrOffset (len $count) exceeds buffer"
                )
            }
            payloadStart = valueOrOffset.toInt()
            return bytes.copyOfRange(payloadStart, payloadStart + count)
        }

        return null
    }

    private fun readUint16(bytes: ByteArray, pos: Int, littleEndian: Boolean): Int {
        val b0 = bytes[pos].toInt() and 0xFF
        val b1 = bytes[pos + 1].toInt() and 0xFF
        return if (littleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
    }

    private fun readUint32(bytes: ByteArray, pos: Int, littleEndian: Boolean): Long {
        val b0 = bytes[pos].toLong() and 0xFF
        val b1 = bytes[pos + 1].toLong() and 0xFF
        val b2 = bytes[pos + 2].toLong() and 0xFF
        val b3 = bytes[pos + 3].toLong() and 0xFF
        return if (littleEndian) {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }
}
