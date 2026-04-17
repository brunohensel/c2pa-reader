package com.brunohensel.c2pareader.jumbf

/**
 * Parses a raw JUMBF (JPEG Universal Metadata Box Format, ISO/IEC 19566-5) byte stream
 * into a typed [JumbfSuperbox] tree. The stream is expected to be a single top-level
 * superbox — which is exactly what C2PA ships: a `jumb` box labeled "c2pa" carrying
 * the manifest store.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the box-walking path in c2pa-rs:
 * [sdk/src/jumbf/boxes.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/jumbf/boxes.rs).
 *
 * ## JUMBF box wire format (ISOBMFF-derived)
 *
 * Every box starts with an 8-byte header:
 *
 * ```
 *  0       4       8
 * +-------+-------+-----------------+
 * | LBox  | TBox  |     payload     |
 * +-------+-------+-----------------+
 * 4 bytes  4 bytes
 * ```
 *
 * - **LBox** (4 bytes, big-endian unsigned): total size of the box, *including* the
 *   LBox/TBox fields themselves. Three special values:
 *   - `1` → an 8-byte extended length (**XLBox**) follows the TBox. Used for boxes
 *     larger than ~4 GB.
 *   - `0` → the box runs to the end of its containing box/file. Only ever seen on the
 *     outermost box.
 *   - `<8` → invalid (the header alone is 8 bytes).
 * - **TBox** (4 bytes): the ASCII four-character box type code, e.g. `jumb`, `jumd`, `cbor`.
 *
 * ## Superbox (`jumb`) interior
 *
 * A `jumb` superbox must contain exactly one description box (`jumd`) as its first
 * child, followed by zero or more content boxes and/or nested `jumb` superboxes.
 * The `jumd` carries a 16-byte content-type UUID, a 1-byte toggle mask, and — when
 * the toggle's LABEL bit is set — a null-terminated UTF-8 label string.
 *
 * ```
 * jumd payload:
 * +----------------+-------+------------------+---------+...
 * | contentTypeUUID| toggle|  label (optional)|   id    |
 * +----------------+-------+------------------+---------+
 *     16 bytes      1 byte    UTF-8 + NUL       4 bytes
 * ```
 *
 * Only the label is surfaced on [JumbfSuperbox.label]; the UUID and other optional
 * fields are ignored for Phase 1 because C2PA dispatch happens by label.
 */
internal object JumbfParser {

    private const val JUMB_TYPE = "jumb"
    private const val JUMD_TYPE = "jumd"

    // Toggle bit mask inside the jumd payload. Position 0 = "requestable", 1 = "label present",
    // 2 = "ID present", 3 = "signature present", 4 = "private box present".
    private const val JUMD_TOGGLE_LABEL_PRESENT = 0x02

    fun parse(bytes: ByteArray): JumbfSuperbox {
        val r = Reader(bytes)
        val box = parseBox(r, containerEnd = bytes.size.toLong())
        if (box !is JumbfSuperbox) {
            throw JumbfParseException("top-level JUMBF box is '${box.type}', expected '$JUMB_TYPE'")
        }
        if (!r.isEof) {
            throw JumbfParseException("unexpected ${r.remaining} trailing byte(s) after top-level box")
        }
        return box
    }

    private fun parseBox(r: Reader, containerEnd: Long): JumbfBox {
        val boxStart = r.pos
        ensureRemaining(r, 8, containerEnd, what = "box header")

        val lbox = r.readUInt32()
        val tbox = r.readFourCC()

        val boxLength = when {
            lbox == 0L -> containerEnd - boxStart  // box runs to end of container
            lbox == 1L -> {
                ensureRemaining(r, 8, containerEnd, what = "XLBox")
                val xl = r.readUInt64Signed()
                if (xl < 16L) throw JumbfParseException("XLBox $xl below minimum (16)")
                xl
            }
            lbox < 8L -> throw JumbfParseException("LBox $lbox below minimum (8) for box '$tbox'")
            else -> lbox
        }
        val boxEnd = boxStart + boxLength
        if (boxEnd > containerEnd) {
            throw JumbfParseException(
                "box '$tbox' claims length $boxLength, exceeds container by ${boxEnd - containerEnd} bytes"
            )
        }

        return if (tbox == JUMB_TYPE) {
            parseSuperboxInterior(r, boxEnd)
        } else {
            val payloadSize = (boxEnd - r.pos).toInt()
            val payload = r.readBytes(payloadSize)
            JumbfContentBox(tbox, payload)
        }
    }

    private fun parseSuperboxInterior(r: Reader, boxEnd: Long): JumbfSuperbox {
        if (r.pos >= boxEnd) throw JumbfParseException("'$JUMB_TYPE' superbox has no children")

        var label: String? = null
        var sawJumd = false
        val children = mutableListOf<JumbfBox>()

        while (r.pos < boxEnd) {
            val child = parseBox(r, boxEnd)
            if (child is JumbfContentBox && child.type == JUMD_TYPE) {
                if (sawJumd) throw JumbfParseException("superbox has multiple '$JUMD_TYPE' descriptions")
                sawJumd = true
                label = extractLabel(child.payload)
            } else {
                if (!sawJumd) {
                    throw JumbfParseException(
                        "superbox first child is '${child.type}', expected '$JUMD_TYPE'"
                    )
                }
                children.add(child)
            }
        }

        if (!sawJumd) throw JumbfParseException("superbox missing '$JUMD_TYPE' description")

        return JumbfSuperbox(label = label, children = children)
    }

    /**
     * Extracts the optional label from a `jumd` payload. Layout: 16-byte content-type UUID,
     * 1-byte toggle mask, and — when the LABEL bit (0x02) is set in the toggle — a
     * null-terminated UTF-8 string. Returns null if the box has no label.
     */
    private fun extractLabel(jumdPayload: ByteArray): String? {
        if (jumdPayload.size < 17) {
            throw JumbfParseException("'$JUMD_TYPE' payload ${jumdPayload.size} bytes, needs at least 17")
        }
        val toggle = jumdPayload[16].toInt() and 0xFF
        if ((toggle and JUMD_TOGGLE_LABEL_PRESENT) == 0) return null

        val labelStart = 17
        var labelEnd = labelStart
        while (labelEnd < jumdPayload.size && jumdPayload[labelEnd] != 0.toByte()) {
            labelEnd++
        }
        if (labelEnd >= jumdPayload.size) {
            throw JumbfParseException("'$JUMD_TYPE' label is not null-terminated")
        }
        return jumdPayload.copyOfRange(labelStart, labelEnd).decodeToString()
    }

    private fun ensureRemaining(r: Reader, n: Int, containerEnd: Long, what: String) {
        if (containerEnd - r.pos < n) {
            throw JumbfParseException("truncated $what: ${containerEnd - r.pos} byte(s) remaining, needed $n")
        }
    }
}

/** Positional reader over a JUMBF byte stream. */
private class Reader(private val bytes: ByteArray) {
    var pos: Int = 0
        private set

    val isEof: Boolean get() = pos >= bytes.size
    val remaining: Int get() = bytes.size - pos

    fun readUInt32(): Long {
        if (pos + 4 > bytes.size) throw JumbfParseException("unexpected end of JUMBF input (uint32)")
        val v = ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)
        pos += 4
        return v
    }

    /**
     * Reads 8 bytes as a big-endian unsigned 64-bit value, returned as a `Long`. Values larger
     * than [Long.MAX_VALUE] come back as negatives — realistic JUMBF boxes never exceed 2^63
     * bytes, so we treat negative results as invalid and reject them.
     */
    fun readUInt64Signed(): Long {
        if (pos + 8 > bytes.size) throw JumbfParseException("unexpected end of JUMBF input (uint64)")
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        pos += 8
        if (v < 0) throw JumbfParseException("XLBox exceeds Long.MAX_VALUE")
        return v
    }

    /** Reads 4 ASCII bytes and returns them as a string (the JUMBF TBox / FourCC). */
    fun readFourCC(): String {
        if (pos + 4 > bytes.size) throw JumbfParseException("unexpected end of JUMBF input (TBox)")
        val s = buildString(4) {
            for (i in 0 until 4) {
                val b = bytes[pos + i].toInt() and 0x7F
                append(b.toChar())
            }
        }
        pos += 4
        return s
    }

    fun readBytes(n: Int): ByteArray {
        if (n < 0) throw JumbfParseException("negative read length $n")
        if (pos + n > bytes.size) {
            throw JumbfParseException("truncated JUMBF payload: needed $n bytes, have $remaining")
        }
        val result = bytes.copyOfRange(pos, pos + n)
        pos += n
        return result
    }
}
