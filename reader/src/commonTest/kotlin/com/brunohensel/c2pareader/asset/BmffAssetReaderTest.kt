package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BmffAssetReaderTest {

    @Test
    fun cleanBmffWithoutC2paUuidReturnsNull() {
        // Minimal still-image HEIF: ftyp + mdat, no uuid box.
        val bmff = ftyp("heic") + dummyBox("mdat", ByteArray(16))
        assertNull(BmffAssetReader.extractJumbf(bmff))
    }

    @Test
    fun c2paUuidBoxWithManifestPurposeReturnsJumbfBytes() {
        // C2PA uuid payload layout (c2pa-rs bmff_io.rs):
        //   uuid(16) + FullBox(version 1 + flags 3) + purpose(utf-8, null-terminated) +
        //   firstAuxUuidOffset(8,BE) + JUMBF manifest store bytes.
        // The JUMBF bytes are what the reader must return verbatim.
        val jumbf = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val bmff = ftyp("heic") + c2paUuidBox(purpose = "manifest", jumbf = jumbf)
        assertContentEquals(jumbf, BmffAssetReader.extractJumbf(bmff))
    }

    @Test
    fun c2paUuidBoxWithMerklePurposeIsSkipped() {
        // Only `manifest` (and the equivalent `original`/`update`) purposes carry the JUMBF
        // manifest store; `merkle` is content-hash aux data. Skip it.
        val merkle = c2paUuidBoxRaw(purpose = "merkle", payloadAfterPurpose = byteArrayOf(0x01, 0x02))
        val bmff = ftyp("heic") + merkle
        assertNull(BmffAssetReader.extractJumbf(bmff))
    }

    @Test
    fun nonC2paUuidBoxIsSkipped() {
        // Foreign uuid box (any UUID other than C2PA_UUID) — walker must ignore and keep walking.
        val xmpUuid = byteArrayOf(
            0xBE.toByte(), 0x7A.toByte(), 0xCF.toByte(), 0xCB.toByte(),
            0x97.toByte(), 0xA9.toByte(), 0x42.toByte(), 0xE8.toByte(),
            0x9C.toByte(), 0x71.toByte(), 0x99.toByte(), 0x94.toByte(),
            0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
        )
        val foreignUuidBox = uuidBox(extendedType = xmpUuid, payload = byteArrayOf(0x01, 0x02, 0x03))
        val bmff = ftyp("heic") + foreignUuidBox
        assertNull(BmffAssetReader.extractJumbf(bmff))
    }

    @Test
    fun c2paUuidAfterForeignUuidIsStillFound() {
        // Walker must continue past non-C2PA uuid boxes.
        val jumbf = byteArrayOf(0x11, 0x22, 0x33)
        val foreign = uuidBox(extendedType = ByteArray(16) { 0xAA.toByte() }, payload = byteArrayOf(0x00))
        val bmff = ftyp("heic") + foreign + c2paUuidBox(purpose = "manifest", jumbf = jumbf)
        assertContentEquals(jumbf, BmffAssetReader.extractJumbf(bmff))
    }

    @Test
    fun truncatedBoxHeaderThrowsMalformed() {
        // File ends in the middle of what should be an 8-byte box header.
        val truncated = ftyp("heic") + byteArrayOf(0x00, 0x00, 0x00) // 3 bytes, not 8
        assertFailsWith<MalformedAssetException> { BmffAssetReader.extractJumbf(truncated) }
    }

    @Test
    fun declaredBoxSizeExceedsRemainingBytesThrowsMalformed() {
        // A uuid box whose size field claims far more bytes than the buffer actually holds.
        val bogusBox = byteArrayOf(
            0x00, 0x00, 0x10, 0x00,                   // size = 4096
            0x75, 0x75, 0x69, 0x64,                   // "uuid"
        )
        val bmff = ftyp("heic") + bogusBox
        assertFailsWith<MalformedAssetException> { BmffAssetReader.extractJumbf(bmff) }
    }

    @Test
    fun boxSizeBelowHeaderSizeThrowsMalformed() {
        // ISO/IEC 14496-12 §4.2: size values in the range 2..7 are invalid (header alone is 8).
        val bogus = byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x62, 0x61, 0x64, 0x20) // size=4, "bad "
        val bmff = ftyp("heic") + bogus
        assertFailsWith<MalformedAssetException> { BmffAssetReader.extractJumbf(bmff) }
    }

    @Test
    fun largeBoxWithExtendedSizeIsHandled() {
        // ISO/IEC 14496-12 §4.2: size == 1 signals that an 8-byte `largesize` follows the type.
        // We build a minimal large-size uuid box carrying a "manifest" C2PA payload so the walker
        // must consume the extended header correctly.
        val jumbf = byteArrayOf(0xFE.toByte(), 0xED.toByte())
        val bmff = ftyp("heic") + c2paUuidBoxLargeSize(purpose = "manifest", jumbf = jumbf)
        assertContentEquals(jumbf, BmffAssetReader.extractJumbf(bmff))
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Build a minimal `ftyp` box with the given major brand and no compatible brands. */
    private fun ftyp(majorBrand: String): ByteArray {
        require(majorBrand.length == 4)
        val payload = majorBrand.encodeToByteArray() + byteArrayOf(0, 0, 0, 0) // major + minorVersion
        return box("ftyp", payload)
    }

    /** Build a generic box: size(4,BE) + type(4) + payload. */
    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        val size = 8 + payload.size
        return u32BE(size) + type.encodeToByteArray() + payload
    }

    private fun dummyBox(type: String, payload: ByteArray): ByteArray = box(type, payload)

    /** Build a `uuid` box with a 16-byte extended type and arbitrary payload. */
    private fun uuidBox(extendedType: ByteArray, payload: ByteArray): ByteArray {
        require(extendedType.size == 16)
        return box("uuid", extendedType + payload)
    }

    /** Build a C2PA `uuid` box. The payload after the 16-byte UUID follows c2pa-rs bmff_io.rs. */
    private fun c2paUuidBox(purpose: String, jumbf: ByteArray): ByteArray {
        val versionFlags = byteArrayOf(0, 0, 0, 0)
        val purposeBytes = purpose.encodeToByteArray() + byteArrayOf(0x00) // null terminator
        val firstAuxUuidOffset = ByteArray(8) // 8-byte BE offset; value is irrelevant to extraction
        val payload = C2PA_UUID + versionFlags + purposeBytes + firstAuxUuidOffset + jumbf
        return box("uuid", payload)
    }

    /** Build a C2PA uuid box for non-manifest purposes (no 8-byte offset follows). */
    private fun c2paUuidBoxRaw(purpose: String, payloadAfterPurpose: ByteArray): ByteArray {
        val versionFlags = byteArrayOf(0, 0, 0, 0)
        val purposeBytes = purpose.encodeToByteArray() + byteArrayOf(0x00)
        val payload = C2PA_UUID + versionFlags + purposeBytes + payloadAfterPurpose
        return box("uuid", payload)
    }

    /** Build a C2PA uuid box using the size=1 + largesize-8 extended-size header form. */
    private fun c2paUuidBoxLargeSize(purpose: String, jumbf: ByteArray): ByteArray {
        val versionFlags = byteArrayOf(0, 0, 0, 0)
        val purposeBytes = purpose.encodeToByteArray() + byteArrayOf(0x00)
        val firstAuxUuidOffset = ByteArray(8)
        val payload = C2PA_UUID + versionFlags + purposeBytes + firstAuxUuidOffset + jumbf
        // Extended-size header: size(4)=1 + type(4)="uuid" + largesize(8) = 16-byte header.
        val totalSize = 16L + payload.size
        return u32BE(1) + "uuid".encodeToByteArray() + u64BE(totalSize) + payload
    }

    private fun u32BE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun u64BE(v: Long): ByteArray = byteArrayOf(
        ((v ushr 56) and 0xFF).toByte(),
        ((v ushr 48) and 0xFF).toByte(),
        ((v ushr 40) and 0xFF).toByte(),
        ((v ushr 32) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    // Same bytes as c2pa-rs bmff_io.rs C2PA_UUID (content provenance box).
    private val C2PA_UUID: ByteArray = byteArrayOf(
        0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(),
        0x1B.toByte(), 0x0E.toByte(), 0x48.toByte(), 0x3C.toByte(),
        0x92.toByte(), 0x97.toByte(), 0x58.toByte(), 0x28.toByte(),
        0x87.toByte(), 0x7E.toByte(), 0xC4.toByte(), 0x81.toByte(),
    )
}
