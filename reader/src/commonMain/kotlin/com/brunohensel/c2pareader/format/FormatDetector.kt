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
        isJpegXl(bytes) -> ImageFormat.JpegXl
        isSvg(bytes) -> ImageFormat.Svg
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

    // JPEG XL (ISO/IEC 18181-2 §A.1) ISOBMFF container signature. The first top-level box is a
    // 12-byte `JXL ` box whose payload is the fixed sequence `0D 0A 87 0A` (matches PNG's
    // newline/DOS-transfer-trap trick for detecting line-ending mangling in transit). c2pa-rs
    // jpegxl_io.rs stores this as `JXL_CONTAINER_MAGIC`.
    private val JXL_CONTAINER_SIG: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C,
        0x4A, 0x58, 0x4C, 0x20,                         // "JXL "
        0x0D, 0x0A, 0x87.toByte(), 0x0A,
    )

    // JPEG XL (ISO/IEC 18181-1 §9.1) naked codestream sync marker: `FF 0A`. Two bytes is a weak
    // signature, but nothing else on our allow-list starts with it (JPEG is `FF D8`). C2PA bans
    // manifests in naked codestreams, so the reader returns `null` (→ NoManifest) rather than
    // extracting anything.
    private val JXL_CODESTREAM_SIG: ByteArray = byteArrayOf(0xFF.toByte(), 0x0A)

    private fun isJpegXl(bytes: ByteArray): Boolean =
        prefixMatches(bytes, JXL_CONTAINER_SIG) || prefixMatches(bytes, JXL_CODESTREAM_SIG)

    private fun prefixMatches(bytes: ByteArray, sig: ByteArray): Boolean {
        if (bytes.size < sig.size) return false
        for (i in sig.indices) if (bytes[i] != sig[i]) return false
        return true
    }

    // Maximum byte prefix we inspect when sniffing SVG. Real SVGs start with at most a BOM + an
    // XML declaration + a DOCTYPE + a few comments before the root element; 512 bytes comfortably
    // covers that without loading the whole file into the scan.
    private const val SVG_SNIFF_WINDOW: Int = 512

    // SVG is a text format, so we detect by locating the `<svg` root after any XML prologue.
    // Tolerant to: UTF-8 BOM, leading whitespace, `<?xml ... ?>`, `<!DOCTYPE ... >`, `<!-- ... -->`.
    private fun isSvg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var i = 0
        // UTF-8 BOM: EF BB BF.
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        val end = minOf(bytes.size, i + SVG_SNIFF_WINDOW)
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            // Skip ASCII whitespace (space, tab, CR, LF).
            if (b == 0x20 || b == 0x09 || b == 0x0D || b == 0x0A) { i++; continue }
            if (b != 0x3C /* '<' */) return false
            // Next token starts with '<'; figure out which one.
            when {
                startsWith(bytes, i, "<?xml") -> {
                    val pEnd = indexOf(bytes, "?>", i + 5, end)
                    if (pEnd < 0) return false
                    i = pEnd + 2
                }
                startsWith(bytes, i, "<!--") -> {
                    val pEnd = indexOf(bytes, "-->", i + 4, end)
                    if (pEnd < 0) return false
                    i = pEnd + 3
                }
                startsWith(bytes, i, "<!DOCTYPE") || startsWith(bytes, i, "<!doctype") -> {
                    val pEnd = indexOf(bytes, ">", i + 9, end)
                    if (pEnd < 0) return false
                    i = pEnd + 1
                }
                startsWith(bytes, i, "<svg") -> {
                    // Must be followed by whitespace, `>`, or `/` — not `<svgfoo`.
                    if (i + 4 >= bytes.size) return true
                    val next = bytes[i + 4].toInt() and 0xFF
                    return next == 0x20 || next == 0x09 || next == 0x0D || next == 0x0A ||
                        next == 0x3E /* '>' */ || next == 0x2F /* '/' */
                }
                else -> return false
            }
        }
        return false
    }

    private fun startsWith(bytes: ByteArray, offset: Int, literal: String): Boolean {
        if (offset + literal.length > bytes.size) return false
        for (i in literal.indices) {
            if (bytes[offset + i].toInt().toChar() != literal[i]) return false
        }
        return true
    }

    private fun indexOf(bytes: ByteArray, needle: String, from: Int, end: Int): Int {
        val needleBytes = needle.encodeToByteArray()
        val last = end - needleBytes.size
        var i = from
        while (i <= last) {
            var match = true
            for (j in needleBytes.indices) {
                if (bytes[i + j] != needleBytes[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
