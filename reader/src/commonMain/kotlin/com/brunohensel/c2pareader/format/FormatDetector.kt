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
        isHeif(bytes) -> ImageFormat.Heif
        isWebp(bytes) -> ImageFormat.Webp
        isTiff(bytes) -> ImageFormat.Tiff
        // Sidecar probe runs last so it only fires when no image magic matched.
        isC2paSidecar(bytes) -> ImageFormat.C2paSidecar
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

    // ISO/IEC 14496-12 §4.3: an ISOBMFF file's first top-level box is `ftyp`. Layout:
    //   size(4,BE) "ftyp"(4) majorBrand(4) minorVersion(4) compatBrands(4*N)
    // We only need bytes 4–11 (type + major brand) to decide. The C2PA PRD restricts Phase 3 to
    // *still-image* HEIF; video brands like `mp4 `/`mov `/`isom`/`m4v ` fall through to Unknown.
    private val FTYP: ByteArray = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp"

    // Allow-list of still-image BMFF major brands (matches c2pa-rs bmff_io.rs SUPPORTED_TYPES
    // filtered to still-image entries). Kept as ByteArrays to avoid per-call string allocation.
    private val STILL_IMAGE_BMFF_BRANDS: List<ByteArray> = listOf(
        byteArrayOf(0x68, 0x65, 0x69, 0x63), // "heic" — HEIF coded image, HEVC
        byteArrayOf(0x68, 0x65, 0x69, 0x78), // "heix" — HEIF extended image
        byteArrayOf(0x68, 0x65, 0x69, 0x6D), // "heim" — HEIF multiview
        byteArrayOf(0x68, 0x65, 0x69, 0x73), // "heis" — HEIF scalable
        byteArrayOf(0x68, 0x65, 0x69, 0x66), // "heif" — generic HEIF
        byteArrayOf(0x6D, 0x69, 0x66, 0x31), // "mif1" — MIAF still-image (ISO/IEC 23008-12)
        byteArrayOf(0x6D, 0x73, 0x66, 0x31), // "msf1" — MIAF sequence (still-image sequences)
        byteArrayOf(0x61, 0x76, 0x69, 0x66), // "avif" — AVIF still image (ISO/IEC 23000-22)
    )

    private fun isHeif(bytes: ByteArray): Boolean {
        // Need at least: ftyp box header (8) + 4-byte major brand = 12 bytes.
        if (bytes.size < 12) return false
        // Bytes 4–7 must spell "ftyp".
        for (i in FTYP.indices) if (bytes[4 + i] != FTYP[i]) return false
        // Bytes 8–11 are the major brand; check against the still-image allow-list.
        for (brand in STILL_IMAGE_BMFF_BRANDS) {
            var matches = true
            for (i in brand.indices) {
                if (bytes[8 + i] != brand[i]) { matches = false; break }
            }
            if (matches) return true
        }
        return false
    }

    // RIFF container (Microsoft Multimedia): "RIFF"(4) + fileSize(4,LE) + formType(4).
    // WebP's form type is "WEBP"; WAVE audio, AVI video, and other RIFF payloads must not match.
    private val RIFF: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val WEBP: ByteArray = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"

    private fun isWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        for (i in RIFF.indices) if (bytes[i] != RIFF[i]) return false
        for (i in WEBP.indices) if (bytes[8 + i] != WEBP[i]) return false
        return true
    }

    // TIFF 6.0 §2: 2-byte byte-order marker ("II" = little-endian, "MM" = big-endian) followed
    // by the magic number 42 in the chosen endianness. DNG shares the exact same header format.
    // Little-endian: 49 49 2A 00. Big-endian: 4D 4D 00 2A.
    private val TIFF_LE: ByteArray = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE: ByteArray = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)

    private fun isTiff(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return matches(bytes, 0, TIFF_LE) || matches(bytes, 0, TIFF_BE)
    }

    private fun matches(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }

    // JUMBF top-level superbox type code "jumb" (ISO/IEC 19566-5) in ASCII. A C2PA standalone
    // `.c2pa` sidecar starts with a 4-byte LBox then this 4-byte TBox at offset 4. We only
    // sniff the TBox here — deeper structural validity (label, children) is verified by
    // JumbfParser later and surfaced as C2paError.JumbfError if the bytes turn out malformed.
    private val JUMB_TBOX: ByteArray = byteArrayOf(0x6A, 0x75, 0x6D, 0x62)

    private fun isC2paSidecar(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in JUMB_TBOX.indices) {
            if (bytes[4 + i] != JUMB_TBOX[i]) return false
        }
        return true
    }
}
