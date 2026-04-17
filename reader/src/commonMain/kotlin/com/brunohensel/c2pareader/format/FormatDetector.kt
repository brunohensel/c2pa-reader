package com.brunohensel.c2pareader.format

/**
 * Classifies the input by inspecting its **magic number** — the fixed byte prefix
 * that every file of a given format begins with (independent of encoder, platform,
 * or subtype). Adding a new format = one branch here + one new [ImageFormat] variant.
 */
internal object FormatDetector {
    fun detect(bytes: ByteArray): ImageFormat = when {
        isJpeg(bytes) -> ImageFormat.Jpeg
        else -> ImageFormat.Unknown
    }

    // JPEG Start of Image marker: `FF D8` at offset 0. Defined by ITU-T T.81 / ISO 10918-1.
    // `.toByte()` is required because 0xFF (255) doesn't fit in Kotlin's signed Byte (-128..127);
    // the cast reinterprets the 8 bits as -1. The underlying bit pattern is unchanged.
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
}
