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
        // RIFF (ITU-T Rec. H.264 Annex A; Microsoft Multimedia): "RIFF" + size(LE,4) + formType(4).
        // formType "WEBP" (RFC-ish: libwebp container spec) classifies as ImageFormat.Webp.
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
