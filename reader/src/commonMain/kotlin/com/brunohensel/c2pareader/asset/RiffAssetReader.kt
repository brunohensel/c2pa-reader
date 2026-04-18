package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a WebP asset by walking its RIFF chunk
 * stream and returning the payload of the first `C2PA` chunk.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/riff_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/riff_io.rs).
 * The `C2PA` chunk fourCC comes from `C2PA_CHUNK_ID` in that file.
 *
 * ## RIFF layout (Microsoft Multimedia Programming Interface, libwebp container spec)
 *
 * ```
 * "RIFF" (4)  fileSize (4, LE)  formType (4)  <chunks…>
 * ```
 *
 * `fileSize` is the byte count of everything after itself, i.e. `formType + chunks`. Each chunk:
 *
 * ```
 * fourCC (4)  chunkSize (4, LE)  payload (chunkSize bytes)  [pad 1 byte if chunkSize is odd]
 * ```
 *
 * The pad byte is NOT counted in `chunkSize`; the walker must advance `chunkSize + (size & 1)`
 * bytes past the header to reach the next chunk. All integers in RIFF are little-endian.
 *
 * C2PA piggy-backs on WebP only (audio/video RIFF forms — WAVE, AVI — are explicitly out of
 * scope). [FormatDetector] gates on the form type before we get here; this reader re-validates
 * defensively.
 */
internal object RiffAssetReader : AssetReader {

    private val RIFF_MAGIC: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val WEBP_FORM: ByteArray = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"
    private val C2PA_ID: ByteArray = byteArrayOf(0x43, 0x32, 0x50, 0x41)   // "C2PA"

    // RIFF header = magic(4) + fileSize(4) + formType(4).
    private const val RIFF_HEADER_SIZE: Int = 12

    // Chunk header = fourCC(4) + chunkSize(4).
    private const val CHUNK_HEADER_SIZE: Int = 8

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        if (bytes.size < RIFF_HEADER_SIZE) {
            throw MalformedAssetException("buffer too short for RIFF header")
        }
        for (i in RIFF_MAGIC.indices) {
            if (bytes[i] != RIFF_MAGIC[i]) {
                throw MalformedAssetException("not a RIFF file (missing 'RIFF' magic)")
            }
        }
        for (i in WEBP_FORM.indices) {
            if (bytes[8 + i] != WEBP_FORM[i]) {
                val formCc = readFourCc(bytes, 8)
                throw MalformedAssetException("unsupported RIFF form type '$formCc' (WebP only)")
            }
        }

        var pos = RIFF_HEADER_SIZE
        while (pos < bytes.size) {
            if (pos + CHUNK_HEADER_SIZE > bytes.size) {
                throw MalformedAssetException("truncated RIFF chunk header at offset $pos")
            }
            val chunkSizeLong = readUint32LE(bytes, pos + 4)
            if (chunkSizeLong < 0L || chunkSizeLong > Int.MAX_VALUE.toLong()) {
                throw MalformedAssetException("invalid RIFF chunk size $chunkSizeLong at offset $pos")
            }
            val chunkSize = chunkSizeLong.toInt()
            val payloadStart = pos + CHUNK_HEADER_SIZE
            val payloadEnd = payloadStart + chunkSize
            if (payloadEnd > bytes.size) {
                throw MalformedAssetException(
                    "RIFF chunk at offset $pos claims size $chunkSize, exceeds remaining bytes"
                )
            }
            if (matches(bytes, pos, C2PA_ID)) {
                return bytes.copyOfRange(payloadStart, payloadEnd)
            }
            // Advance past payload + the pad byte (present when chunkSize is odd).
            pos = payloadEnd + (chunkSize and 1)
        }
        return null
    }

    private fun matches(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }

    private fun readUint32LE(bytes: ByteArray, pos: Int): Long =
        (bytes[pos].toLong() and 0xFF) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 8) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 3].toLong() and 0xFF) shl 24)

    private fun readFourCc(bytes: ByteArray, pos: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[pos + i].toInt() and 0xFF).toChar())
    }
}
