package com.brunohensel.c2pareader.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDetectorTest {
    @Test
    fun jpegSoiMarkerIsDetected() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(ImageFormat.Jpeg, FormatDetector.detect(bytes))
    }

    @Test
    fun pngSignatureIsDetected() {
        // PNG signature per ISO/IEC 15948: 89 50 4E 47 0D 0A 1A 0A
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals(ImageFormat.Png, FormatDetector.detect(bytes))
    }

    @Test
    fun pngSignatureWithTruncatedTailIsUnknown() {
        // First 7 bytes of the PNG signature — not enough to confirm PNG.
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A,
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun jumbSignatureAtOffsetFourIsSidecar() {
        // Any 4-byte length prefix followed by ASCII "jumb" (0x6A 0x75 0x6D 0x62) — the JUMBF
        // top-level superbox type — is recognized as a standalone `.c2pa` sidecar.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,                   // LBox = 32 (arbitrary, non-zero)
            0x6A, 0x75, 0x6D, 0x62,                   // TBox = "jumb"
            0x00, 0x00, 0x00, 0x00,                   // stub payload
        )
        assertEquals(ImageFormat.C2paSidecar, FormatDetector.detect(bytes))
    }

    @Test
    fun nonJumbAtOffsetFourIsUnknown() {
        // Same shape as above but TBox is "free" — not a JUMBF superbox.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            0x66, 0x72, 0x65, 0x65,
            0x00, 0x00, 0x00, 0x00,
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun sevenByteBufferIsUnknown() {
        // Too short for either PNG signature (8) or a JUMBF box header (8). Should not be
        // misclassified as a sidecar due to an out-of-bounds read.
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x6A, 0x75, 0x6D)
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun arbitraryBytesAreUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun emptyBytesAreUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf()))
    }

    @Test
    fun singleByteIsUnknown() {
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(byteArrayOf(0xFF.toByte())))
    }

    // --- HEIF / HEIC (ISOBMFF) ----------------------------------------------------------------

    @Test
    fun heicMajorBrandIsDetectedAsHeif() {
        // ISO/IEC 14496-12 §4.3: the first top-level box is `ftyp`, whose payload starts with
        // the major brand. Layout: size(4) "ftyp"(4) majorBrand(4) minorVersion(4) compatBrands…
        // `heic` is a still-image HEIF brand → should classify as ImageFormat.Heif.
        val bytes = ftyp(majorBrand = "heic", minorVersion = byteArrayOf(0, 0, 0, 0))
        assertEquals(ImageFormat.Heif, FormatDetector.detect(bytes))
    }

    @Test
    fun mif1MajorBrandIsDetectedAsHeif() {
        // `mif1` is the generic MIAF still-image brand (ISO/IEC 23008-12). Accept as HEIF.
        val bytes = ftyp(majorBrand = "mif1", minorVersion = byteArrayOf(0, 0, 0, 0))
        assertEquals(ImageFormat.Heif, FormatDetector.detect(bytes))
    }

    @Test
    fun avifMajorBrandIsDetectedAsHeif() {
        // AVIF (ISO/IEC 23000-22) is still-image ISOBMFF and carries C2PA the same way HEIF does.
        val bytes = ftyp(majorBrand = "avif", minorVersion = byteArrayOf(0, 0, 0, 0))
        assertEquals(ImageFormat.Heif, FormatDetector.detect(bytes))
    }

    @Test
    fun mp4MajorBrandIsUnknown() {
        // Video BMFF brands (mp4/mov/m4v/isom) are rejected — PRD out-of-scope for Phase 3.
        val bytes = ftyp(majorBrand = "mp42", minorVersion = byteArrayOf(0, 0, 0, 0))
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun quicktimeMajorBrandIsUnknown() {
        val bytes = ftyp(majorBrand = "qt  ", minorVersion = byteArrayOf(0, 0, 0, 0))
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun ftypTooShortIsUnknown() {
        // `ftyp` is present but the buffer is cut before the major brand. Must not be
        // misclassified via out-of-bounds reads.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,                   // size
            0x66, 0x74, 0x79, 0x70,                   // "ftyp"
            0x68,                                     // only 1 byte of major brand
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    // --- WebP (RIFF) --------------------------------------------------------------------------

    @Test
    fun webpRiffIsDetected() {
        // RIFF (Microsoft Multimedia Programming Interface): "RIFF" + size(LE,4) + formType(4).
        // formType "WEBP" (libwebp container spec) classifies as ImageFormat.Webp.
        val bytes = "RIFF".encodeToByteArray() +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "WEBP".encodeToByteArray()
        assertEquals(ImageFormat.Webp, FormatDetector.detect(bytes))
    }

    @Test
    fun waveRiffIsUnknown() {
        // Audio RIFF (WAVE) is explicitly out of scope — must not match WebP.
        val bytes = "RIFF".encodeToByteArray() +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "WAVE".encodeToByteArray()
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun aviRiffIsUnknown() {
        val bytes = "RIFF".encodeToByteArray() +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "AVI ".encodeToByteArray()
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    @Test
    fun riffTooShortForFormTypeIsUnknown() {
        val bytes = "RIFF".encodeToByteArray() + byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x57, 0x45)
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    // --- TIFF / DNG ---------------------------------------------------------------------------

    @Test
    fun littleEndianTiffHeaderIsDetected() {
        // TIFF 6.0 §2: byte-order "II" (0x4949) + magic 42 in little-endian = 0x2A 0x00 + IFD off.
        val bytes = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals(ImageFormat.Tiff, FormatDetector.detect(bytes))
    }

    @Test
    fun bigEndianTiffHeaderIsDetected() {
        // Big-endian TIFF: "MM" + magic 42 as 0x00 0x2A.
        val bytes = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        assertEquals(ImageFormat.Tiff, FormatDetector.detect(bytes))
    }

    @Test
    fun tiffWithWrongMagicIsUnknown() {
        // "II" but magic isn't 42 — not a TIFF.
        val bytes = byteArrayOf(0x49, 0x49, 0x2B, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    // --- JPEG XL ------------------------------------------------------------------------------

    @Test
    fun jxlContainerSignatureIsDetected() {
        // ISO/IEC 18181-2 §A.1: a JXL ISOBMFF container starts with the fixed 12-byte "JXL " box
        // `00 00 00 0C 4A 58 4C 20 0D 0A 87 0A`. Anything that follows is subsequent top-level
        // boxes, not part of the signature.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C,
            0x4A, 0x58, 0x4C, 0x20,
            0x0D, 0x0A, 0x87.toByte(), 0x0A,
            // a following `ftyp` box (not required for detection, but realistic)
            0x00, 0x00, 0x00, 0x14,
            0x66, 0x74, 0x79, 0x70,
            0x6A, 0x78, 0x6C, 0x20, 0, 0, 0, 0,
        )
        assertEquals(ImageFormat.JpegXl, FormatDetector.detect(bytes))
    }

    @Test
    fun jxlCodestreamSyncMarkerIsDetected() {
        // ISO/IEC 18181-1 §9.1: the naked-codestream sync marker is `FF 0A`. Only 2 bytes, so
        // the detector stakes on the fact that no other entry in the allow-list starts with it.
        val bytes = byteArrayOf(0xFF.toByte(), 0x0A, 0x00, 0x00)
        assertEquals(ImageFormat.JpegXl, FormatDetector.detect(bytes))
    }

    @Test
    fun jxlContainerSignatureWithBrokenMagicIsUnknown() {
        // Same shape as a JXL container but the trailing `0D 0A 87 0A` quartet is scrambled —
        // must not classify as JXL (or anything else).
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C,
            0x4A, 0x58, 0x4C, 0x20,
            0x00, 0x00, 0x00, 0x00,
        )
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bytes))
    }

    // --- SVG ----------------------------------------------------------------------------------

    @Test
    fun svgRootElementIsDetected() {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg"/>""".encodeToByteArray()
        assertEquals(ImageFormat.Svg, FormatDetector.detect(bytes))
    }

    @Test
    fun svgWithXmlDeclarationAndDoctypeIsDetected() {
        // Typical SVG file shape from editors like Inkscape: XML declaration, optional DOCTYPE,
        // then the root element. The sniffer must skip over both to find `<svg`.
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            |<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""".trimMargin()
        assertEquals(ImageFormat.Svg, FormatDetector.detect(svg.encodeToByteArray()))
    }

    @Test
    fun svgWithLeadingWhitespaceAndCommentsIsDetected() {
        val svg = "\n  <!-- created by hand -->\n<svg/>"
        assertEquals(ImageFormat.Svg, FormatDetector.detect(svg.encodeToByteArray()))
    }

    @Test
    fun svgWithUtf8BomIsDetected() {
        // UTF-8 BOM (EF BB BF) is optional on XML files; the sniffer must skip it rather than
        // treating it as arbitrary leading bytes.
        val body = "<svg/>".encodeToByteArray()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(ImageFormat.Svg, FormatDetector.detect(bom + body))
    }

    @Test
    fun xmlThatIsNotSvgIsUnknown() {
        val xml = """<?xml version="1.0"?><note>not svg</note>""".encodeToByteArray()
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(xml))
    }

    @Test
    fun svgPrefixOfLongerElementNameIsUnknown() {
        // Guards against a naive indexOf("<svg") matching `<svglike` and mis-classifying.
        val bogus = """<svglike xmlns="x"/>""".encodeToByteArray()
        assertEquals(ImageFormat.Unknown, FormatDetector.detect(bogus))
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Build a minimal ISOBMFF file starting with a single `ftyp` box (no body beyond major+minor). */
    private fun ftyp(majorBrand: String, minorVersion: ByteArray, compat: List<String> = emptyList()): ByteArray {
        require(majorBrand.length == 4) { "major brand must be a 4-char FourCC" }
        require(minorVersion.size == 4) { "minorVersion must be 4 bytes" }
        val compatBytes = compat.fold(ByteArray(0)) { acc, b ->
            require(b.length == 4) { "compat brands must be 4-char FourCCs" }
            acc + b.encodeToByteArray()
        }
        val boxSize = 8 + 4 + 4 + compatBytes.size // header + major + minor + compat
        return byteArrayOf(
            ((boxSize ushr 24) and 0xFF).toByte(),
            ((boxSize ushr 16) and 0xFF).toByte(),
            ((boxSize ushr 8) and 0xFF).toByte(),
            (boxSize and 0xFF).toByte(),
        ) + "ftyp".encodeToByteArray() + majorBrand.encodeToByteArray() + minorVersion + compatBytes
    }
}
