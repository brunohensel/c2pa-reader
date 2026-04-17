package com.brunohensel.c2pareader.asset

/**
 * Extracts the raw JUMBF manifest-store bytes from a JPEG asset by walking the JPEG
 * marker segments, collecting every `APP11` segment whose identifier is "JP"
 * (JUMBF-in-JPEG transport per ISO/IEC 19566-5), and reassembling them in
 * packet-sequence order.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/jpeg_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/jpeg_io.rs).
 * Behavior questions should be settled by reading that file; we intentionally match its
 * decisions (which markers to skip, how to group fragments, how to handle malformed inputs).
 *
 * ## JPEG primer (only the parts we care about)
 *
 * A JPEG file is a stream of **segments**. Each segment begins with a 2-byte **marker**
 * of the form `FF xx` where `xx` is the marker code. The whole file looks like:
 *
 * ```
 * FF D8                   SOI (Start of Image) — always first, no payload, no length
 * FF E0 <len> <payload>   APP0 — the JFIF header in most JPEGs
 * FF EB <len> <payload>   APP11 — what C2PA uses
 * FF DB <len> <payload>   DQT  — quantization tables
 * ...                     more segments (DHT, SOF0, ...)
 * FF DA <len> <payload>   SOS  (Start of Scan) — then the compressed image data follows
 * [entropy-coded data]    the actual pixels, no length field, runs until EOI
 * FF D9                   EOI (End of Image) — always last, no payload, no length
 * ```
 *
 * For length-prefixed segments, the 2-byte length **includes** itself but **excludes**
 * the marker. So `<payload size> = <len> - 2`. Lengths are big-endian.
 *
 * C2PA manifests live in `APP11` segments before `SOS`, so this walker stops at `SOS`
 * without having to understand entropy-coded data.
 *
 * ## APP11 "JP" segment layout (ISO 19566-5 / JPEG Systems)
 *
 * A single JUMBF box (the C2PA manifest store) can be larger than a single APP11 segment
 * (which is capped at ~64 KB by the JPEG marker-length field). So the box is split across
 * multiple APP11 fragments sharing the same **box instance number** (En). The packet
 * sequence number (Z) orders them.
 *
 * ```
 * FF EB                   APP11 marker
 * <len: 2 bytes, BE>      segment length, includes itself
 * 4A 50                   CI = "JP" — identifies this APP11 as JUMBF transport
 * <En: 2 bytes, BE>       box instance number (same across all fragments of one box)
 * <Z:  4 bytes, BE>       packet sequence number, starts at 1
 * <LBox: 4 bytes, BE>     redundant per-fragment copy of the superbox length
 * <TBox: 4 bytes>         redundant per-fragment copy of the superbox type
 * <payload>               chunk of the JUMBF box contents
 * ```
 *
 * **LBox/TBox appear in every fragment, not only in Z=1** — see c2pa-rs `jpeg_io.rs`
 * `read_cai()`. Reassembly keeps LBox+TBox from the first fragment (so the output starts
 * with a valid JUMBF box header) and strips them from continuation fragments (so bytes
 * aren't duplicated).
 *
 * APP11 segments with a different CI (e.g. "XT" for JPEG XT tonemapping) are silently
 * ignored — they exist in the wild and are not our concern.
 */
internal object JpegAssetReader : AssetReader {

    private const val SOI_MARKER: Int = 0xD8
    private const val EOI_MARKER: Int = 0xD9
    private const val SOS_MARKER: Int = 0xDA
    private const val APP11_MARKER: Int = 0xEB
    private const val TEM_MARKER: Int = 0x01 // "temporary use" standalone marker, no length field

    // "JP" in ASCII — the JUMBF-in-JPEG common identifier, per ISO 19566-5.
    private const val CI_JP: Int = 0x4A50

    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        requireSoi(bytes)

        // Grouped by En (box instance number) → sorted by Z (packet sequence) → concatenated payloads.
        val fragments: MutableMap<Int, MutableMap<Long, ByteArray>> = mutableMapOf()

        var pos = 2 // past the SOI marker
        while (pos < bytes.size) {
            // Between segments the spec allows any number of 0xFF "fill bytes" before the actual marker byte.
            pos = skipFillBytes(bytes, pos)
            if (pos >= bytes.size) break

            val marker = bytes[pos].toInt() and 0xFF
            pos++ // consume marker code byte

            when (marker) {
                SOI_MARKER -> continue // extra SOI; unusual but harmless
                EOI_MARKER -> break    // end of image
                SOS_MARKER -> break    // start of compressed data, no more APP segments
                in 0xD0..0xD7 -> continue // RSTn restart markers have no length field
                TEM_MARKER -> continue    // standalone "temporary use" marker, no length field

                APP11_MARKER -> {
                    val segmentEnd = readSegmentEnd(bytes, pos)
                    parseApp11(bytes, pos + 2, segmentEnd, fragments)
                    pos = segmentEnd
                }

                else -> {
                    // Every other marker here has a length-prefixed payload. We don't need its
                    // contents; just skip it. Reserved markers 0x02..0xBF don't appear in valid
                    // JPEG streams.
                    val segmentEnd = readSegmentEnd(bytes, pos)
                    pos = segmentEnd
                }
            }
        }

        if (fragments.isEmpty()) return null

        // C2PA ships a single JUMBF manifest store per asset, so one En group is expected.
        // If more than one exists, prefer the first in insertion order.
        val group = fragments.values.first()
        return assemble(group)
    }

    private fun requireSoi(bytes: ByteArray) {
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            throw MalformedAssetException("not a JPEG (missing SOI marker FF D8)")
        }
    }

    private fun skipFillBytes(bytes: ByteArray, start: Int): Int {
        var p = start
        while (p < bytes.size && bytes[p] == 0xFF.toByte()) p++
        return p
    }

    /**
     * Reads the 2-byte big-endian segment length at [pos] and returns the absolute end offset
     * (i.e. the byte immediately past the last byte of the segment payload). Throws if the
     * declared length is truncated by the file.
     */
    private fun readSegmentEnd(bytes: ByteArray, pos: Int): Int {
        if (pos + 1 >= bytes.size) throw MalformedAssetException("truncated segment length field")
        val length = readUint16BE(bytes, pos)
        if (length < 2) throw MalformedAssetException("invalid segment length $length")
        val end = pos + length
        if (end > bytes.size) throw MalformedAssetException("segment length $length exceeds remaining bytes")
        return end
    }

    /**
     * Parses a single APP11 segment payload (starting immediately after the 2-byte length field)
     * and, if it carries a JUMBF fragment ("JP" common identifier), records the fragment bytes
     * into [fragments] keyed by En (box instance) and Z (packet sequence).
     */
    private fun parseApp11(
        bytes: ByteArray,
        payloadStart: Int,
        segmentEnd: Int,
        fragments: MutableMap<Int, MutableMap<Long, ByteArray>>,
    ) {
        // Minimum APP11-JP payload: CI(2) + En(2) + Z(4) = 8 bytes before any JUMBF content.
        if (segmentEnd - payloadStart < 2) {
            throw MalformedAssetException("APP11 segment too short for common identifier")
        }
        val ci = readUint16BE(bytes, payloadStart)
        if (ci != CI_JP) return // not ours; ignore

        val ciEnZEnd = payloadStart + 2 + 2 + 4 // past CI + En + Z
        if (segmentEnd < ciEnZEnd) {
            throw MalformedAssetException("APP11 'JP' segment too short for En/Z header")
        }
        val en = readUint16BE(bytes, payloadStart + 2)
        val z = readUint32BE(bytes, payloadStart + 4)

        // Every fragment carries a redundant 8-byte LBox+TBox copy of the JUMBF superbox
        // header. Keep it from Z=1 so the reassembled byte stream begins with a valid box
        // header; drop it from continuation fragments to avoid duplication.
        val chunkStart = if (z == 1L) ciEnZEnd else {
            val skipLboxTbox = ciEnZEnd + 8
            if (segmentEnd < skipLboxTbox) {
                throw MalformedAssetException("APP11 continuation fragment too short for LBox/TBox skip")
            }
            skipLboxTbox
        }
        val payload = bytes.copyOfRange(chunkStart, segmentEnd)
        fragments.getOrPut(en) { mutableMapOf() }[z] = payload
    }

    private fun assemble(group: Map<Long, ByteArray>): ByteArray {
        val sorted = group.toSortedMap()
        val total = sorted.values.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (payload in sorted.values) {
            payload.copyInto(out, offset)
            offset += payload.size
        }
        return out
    }

    private fun readUint16BE(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)

    private fun readUint32BE(bytes: ByteArray, pos: Int): Long =
        ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)
}
