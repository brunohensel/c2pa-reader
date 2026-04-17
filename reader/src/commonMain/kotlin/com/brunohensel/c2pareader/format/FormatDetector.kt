package com.brunohensel.c2pareader.format

/**
 * Classifies the input by inspecting its **magic number** — the fixed byte prefix
 * that every file of a given format begins with (independent of encoder, platform,
 * or subtype). Adding a new format = one branch here + one new [ImageFormat] variant.
 */
internal object FormatDetector {
    fun detect(bytes: ByteArray): ImageFormat = when {
        isJpeg(bytes) -> ImageFormat.Jpeg
        isPng(bytes) -> ImageFormat.Png
        else -> ImageFormat.Unknown
    }

    // JPEG Start of Image marker: `FF D8` at offset 0. Defined by ITU-T T.81 / ISO 10918-1.
    // `.toByte()` is required because 0xFF (255) doesn't fit in Kotlin's signed Byte (-128..127);
    // the cast reinterprets the 8 bits as -1. The underlying bit pattern is unchanged.
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    // PNG signature: `89 50 4E 47 0D 0A 1A 0A` at offset 0, per ISO/IEC 15948-1 / RFC 2083.
    // The first byte (0x89) has the high bit set to detect 7-bit-channel corruption; bytes 2–4
    // spell "PNG"; the rest are CRLF/SUB/LF to trip up naive text-mode transfers.
    private val PNG_SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }
}
