package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a PNG asset by walking the chunk stream
 * and returning the payload of the first `caBX` (Content Authenticity Information) chunk.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/png_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/png_io.rs).
 * Behavior questions should be settled by reading that file.
 *
 * ## PNG primer (only the parts we care about)
 *
 * A PNG file is an 8-byte signature followed by a sequence of length-prefixed **chunks**:
 *
 * ```
 * 89 50 4E 47 0D 0A 1A 0A    PNG signature (ISO/IEC 15948-1 / RFC 2083)
 * +----+----+--------+----+
 * | L  | T  |  data  | C  |
 * +----+----+--------+----+
 *  4B   4B   L bytes   4B
 *
 * L = payload length (uint32, BE; MSB must be 0)
 * T = 4-byte ASCII chunk type code (e.g. IHDR, caBX, IEND)
 * C = CRC32 over type + data (we ignore it: this is a permissive read-only reader)
 * ```
 *
 * The first chunk is always `IHDR`; the last is always `IEND`. C2PA stores its manifest store
 * as the payload of a `caBX` chunk somewhere in between (registered "C2PA Auxiliary Info"
 * chunk type, lowercase first letter → ancillary, lowercase second letter → private/vendor,
 * per PNG naming convention). Spec allows at most one such chunk; if multiple are seen
 * (malformed producer), the first wins — matching c2pa-rs `png_io.rs`.
 */
internal object PngAssetReader : AssetReader {

    // PNG signature: 89 50 4E 47 0D 0A 1A 0A. Required as the first 8 bytes of any PNG stream.
    private val PNG_SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val SIGNATURE_SIZE: Int = 8

    // Chunk type codes are 4-byte ASCII. C2PA manifest store lives in a `caBX` chunk.
    private const val CHUNK_TYPE_CABX: String = "caBX"
    private const val CHUNK_TYPE_IHDR: String = "IHDR"
    private const val CHUNK_TYPE_IEND: String = "IEND"

    // Each chunk has a fixed 12-byte overhead: length(4) + type(4) + CRC(4).
    private const val CHUNK_HEADER_SIZE: Int = 8
    private const val CHUNK_CRC_SIZE: Int = 4

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        requireSignature(bytes)

        var pos = SIGNATURE_SIZE
        var firstChunk = true
        while (pos < bytes.size) {
            if (pos + CHUNK_HEADER_SIZE > bytes.size) {
                throw MalformedAssetException("truncated PNG chunk header at offset $pos")
            }

            val length = readUint32BE(bytes, pos)
            // PNG spec: length field MSB must be zero, so values > Int.MAX_VALUE are invalid.
            if (length < 0 || length > Int.MAX_VALUE.toLong()) {
                throw MalformedAssetException("invalid PNG chunk length $length at offset $pos")
            }
            val payloadStart = pos + CHUNK_HEADER_SIZE
            val payloadEnd = payloadStart + length.toInt()
            val chunkEnd = payloadEnd + CHUNK_CRC_SIZE
            if (chunkEnd > bytes.size) {
                throw MalformedAssetException(
                    "PNG chunk at offset $pos claims length $length, exceeds remaining bytes"
                )
            }

            val type = readFourCc(bytes, pos + 4)
            // PNG spec requires IHDR as the very first chunk. A different first chunk means the
            // stream is malformed at a level above C2PA — surface it as such rather than sliding
            // through and returning NoManifest.
            if (firstChunk && type != CHUNK_TYPE_IHDR) {
                throw MalformedAssetException(
                    "PNG first chunk is '$type', expected '$CHUNK_TYPE_IHDR'"
                )
            }
            firstChunk = false

            if (type == CHUNK_TYPE_CABX) {
                return bytes.copyOfRange(payloadStart, payloadEnd)
            }
            if (type == CHUNK_TYPE_IEND) {
                return null
            }

            pos = chunkEnd
        }
        // Reaching here means we walked every chunk to the end of the buffer without seeing
        // IEND. PNG spec requires IEND as the terminator; its absence is structural corruption,
        // not "well-formed but carries no manifest".
        throw MalformedAssetException("PNG reached end of buffer without '$CHUNK_TYPE_IEND' chunk")
    }

    private fun requireSignature(bytes: ByteArray) {
        if (bytes.size < SIGNATURE_SIZE) {
            throw MalformedAssetException("not a PNG (buffer shorter than 8-byte signature)")
        }
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw MalformedAssetException("not a PNG (signature mismatch at byte $i)")
            }
        }
    }

    private fun readUint32BE(bytes: ByteArray, pos: Int): Long =
        ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)

    private fun readFourCc(bytes: ByteArray, pos: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[pos + i].toInt() and 0xFF).toChar())
    }
}
