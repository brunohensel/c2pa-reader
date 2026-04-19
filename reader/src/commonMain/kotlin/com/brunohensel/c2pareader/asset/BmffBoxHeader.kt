package com.brunohensel.c2pareader.asset

/**
 * Shared, internal ISOBMFF / JUMBF box-header parsing utilities.
 *
 * ISOBMFF ([ISO/IEC 14496-12 §4.2]) and JUMBF ([ISO/IEC 19566-5]) boxes use the same wire
 * format: a 4-byte big-endian size followed by a 4-byte FourCC type, with an optional
 * 8-byte extended size when `size == 1`. Multiple readers in this package walk that same
 * header ([BmffAssetReader] for HEIF, [JpegXlAssetReader] for JXL containers), and the
 * cost of keeping two copies in sync turned out to be real — reviewers flagged drift risk
 * on PR #11. This file is the single source of truth.
 *
 * ## Why package-private, not exposed
 *
 * The header parsing is an internal detail of the asset-reader pipeline. Callers outside
 * this package (`JumbfParser`, `ManifestJsonBuilder`, `C2paReader`) work on bytes or on
 * the typed JUMBF tree, not raw BMFF headers; there is no need to leak this surface.
 */
internal data class BmffBoxHeader(
    val type: String,
    val payloadStart: Int,
    val boxEnd: Int,
)

/**
 * Reads a single ISOBMFF-style box header at [pos] and returns its type FourCC plus the
 * payload / box-end offsets. [label] is used in the thrown messages to help distinguish
 * malformations that surface in different containers (e.g. "BMFF" vs "JXL"). Throws
 * [MalformedAssetException] for any of:
 *
 * - truncated header (fewer than 8 bytes remain),
 * - truncated extended header (`size == 1` but fewer than 16 bytes remain),
 * - invalid extended `largesize` (< 16, or overflows signed 64-bit),
 * - `size` in the range 2..7 (ISO/IEC 14496-12 §4.2 — the header alone is 8 bytes), or
 * - declared box size that overruns the buffer.
 *
 * `size == 0` is interpreted as "run to end of buffer" — legal only for the outermost
 * box in a container. Callers that walk a bounded region (e.g. a `uuid` box interior)
 * should pass the region's end as the buffer upper bound by slicing before calling, or
 * tolerate the size=0 semantics if the outer walker already bounds subsequent reads.
 */
internal fun readBmffBoxHeader(
    bytes: ByteArray,
    pos: Int,
    label: String = "BMFF",
): BmffBoxHeader {
    if (pos + 8 > bytes.size) {
        throw MalformedAssetException("truncated $label box header at offset $pos")
    }
    val size32 = readUint32BE(bytes, pos)
    val type = readFourCc(bytes, pos + 4)

    val totalSize: Long
    val headerSize: Int
    when (size32) {
        0L -> {
            totalSize = (bytes.size - pos).toLong()
            headerSize = 8
        }
        1L -> {
            if (pos + 16 > bytes.size) {
                throw MalformedAssetException("truncated $label large-box header at offset $pos")
            }
            val largesize = readUint64BE(bytes, pos + 8, label)
            if (largesize < 16L) {
                throw MalformedAssetException("invalid $label largesize $largesize at offset $pos")
            }
            totalSize = largesize
            headerSize = 16
        }
        else -> {
            if (size32 < 8L) {
                throw MalformedAssetException("invalid $label box size $size32 at offset $pos")
            }
            totalSize = size32
            headerSize = 8
        }
    }
    val boxEndLong = pos.toLong() + totalSize
    if (boxEndLong > bytes.size) {
        throw MalformedAssetException(
            "$label box at offset $pos claims size $totalSize, exceeds remaining bytes"
        )
    }
    return BmffBoxHeader(type = type, payloadStart = pos + headerSize, boxEnd = boxEndLong.toInt())
}

internal fun readUint32BE(bytes: ByteArray, pos: Int): Long =
    ((bytes[pos].toLong() and 0xFF) shl 24) or
        ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
        ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
        (bytes[pos + 3].toLong() and 0xFF)

/**
 * Reads an 8-byte big-endian unsigned integer as a [Long]. Values above `Long.MAX_VALUE`
 * come back as negatives after the shift; we reject them — realistic BMFF / JUMBF boxes
 * never exceed 2^63 bytes, so a negative result is corruption, not a legitimate size.
 */
internal fun readUint64BE(bytes: ByteArray, pos: Int, label: String = "BMFF"): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
    if (v < 0L) throw MalformedAssetException("$label largesize overflows Long at offset $pos")
    return v
}

internal fun readFourCc(bytes: ByteArray, pos: Int): String = buildString(4) {
    for (i in 0 until 4) append((bytes[pos + i].toInt() and 0xFF).toChar())
}
