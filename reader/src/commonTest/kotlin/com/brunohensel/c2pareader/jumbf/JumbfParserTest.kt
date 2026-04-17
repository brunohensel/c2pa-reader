package com.brunohensel.c2pareader.jumbf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JumbfParserTest {

    @Test
    fun singleSuperboxWithJsonContentIsParsed() {
        val json = "{\"a\":1}".encodeToByteArray()
        val bytes = superbox(label = "c2pa", content = listOf(contentBox("json", json)))

        val tree = JumbfParser.parse(bytes)

        assertEquals("jumb", tree.type)
        assertEquals("c2pa", tree.label)
        assertEquals(1, tree.children.size)
        val child = tree.children.single() as JumbfContentBox
        assertEquals("json", child.type)
        assertEquals("{\"a\":1}", child.payload.decodeToString())
    }

    @Test
    fun superboxWithoutLabelAllowed() {
        // Toggle with no bits set → no label follows in jumd.
        val bytes = superbox(label = null, content = listOf(contentBox("cbor", byteArrayOf(0x01))))
        val tree = JumbfParser.parse(bytes)
        assertEquals(null, tree.label)
        assertEquals(1, tree.children.size)
    }

    @Test
    fun nestedSuperboxIsPreservedInTree() {
        // Manifest-store shape: outer "c2pa" superbox containing one nested manifest superbox.
        val innerPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val inner = superbox(label = "urn:c2pa:example", content = listOf(contentBox("cbor", innerPayload)))
        val outer = superbox(label = "c2pa", content = listOf(inner))

        val tree = JumbfParser.parse(outer)

        assertEquals("c2pa", tree.label)
        assertEquals(1, tree.children.size)

        val innerBox = tree.children.single() as JumbfSuperbox
        assertEquals("urn:c2pa:example", innerBox.label)
        assertEquals(1, innerBox.children.size)
        val cborChild = innerBox.children.single() as JumbfContentBox
        assertEquals("cbor", cborChild.type)
        assertEquals(listOf(0xAA.toByte(), 0xBB.toByte()), cborChild.payload.toList())
    }

    @Test
    fun topLevelBoxOfWrongTypeThrows() {
        // A `json` content box at the root — not a superbox, so parser should reject it.
        val raw = lengthPrefix("json".encodeToByteArray() + "{\"x\":0}".encodeToByteArray(), type = "json")
        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(raw) }
        assertTrue(ex.reason.contains("top-level"), "got: ${ex.reason}")
    }

    @Test
    fun lboxBelowMinimumThrows() {
        // LBox = 4 (impossible — header alone is 8 bytes).
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x04, // LBox = 4
            0x6A, 0x75, 0x6D, 0x62, // TBox = "jumb"
        )
        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(bytes) }
        assertTrue(ex.reason.contains("below minimum"), "got: ${ex.reason}")
    }

    @Test
    fun lboxExceedingContainerThrows() {
        // LBox declares 200 bytes but input has only 16.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0xC8.toByte(), // LBox = 200
            0x6A, 0x75, 0x6D, 0x62,          // "jumb"
            0x00, 0x00, 0x00, 0x00,          // (garbage payload)
            0x00, 0x00, 0x00, 0x00,
        )
        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(bytes) }
        assertTrue(ex.reason.contains("exceeds container"), "got: ${ex.reason}")
    }

    @Test
    fun truncatedHeaderThrows() {
        // Only 6 bytes — can't even read a full LBox + TBox.
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x6A, 0x75)
        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(bytes) }
        assertTrue(ex.reason.contains("truncated"), "got: ${ex.reason}")
    }

    @Test
    fun superboxMissingJumdDescriptionThrows() {
        // A jumb with a content child but no jumd description first.
        val innerContent = lengthPrefix("cbor".encodeToByteArray() + byteArrayOf(0x01), type = "cbor")
        val outerPayload = innerContent
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, (8 + outerPayload.size).toByte(), // LBox
            0x6A, 0x75, 0x6D, 0x62,                              // "jumb"
        ) + outerPayload

        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(bytes) }
        assertTrue(ex.reason.contains("expected 'jumd'"), "got: ${ex.reason}")
    }

    @Test
    fun trailingBytesAfterTopLevelThrow() {
        val main = superbox(label = "c2pa", content = listOf(contentBox("json", byteArrayOf(0x20))))
        val withTrailing = main + byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(withTrailing) }
        assertTrue(ex.reason.contains("trailing"), "got: ${ex.reason}")
    }

    @Test
    fun nonAsciiTboxByteIsRejected() {
        // TBox = 0xE9 'u' 'm' 'b' — the 0xE9 byte has the high bit set. Before the fix, the
        // reader masked it to 0x69 ('i'), turning an invalid box type into the arbitrary
        // ASCII string "iumb" and misrouting the parse. It now fails fast with a precise reason.
        val payload = ByteArray(8) // any 8 bytes — the parse should fail before reaching them
        val bytes = u32BE(16L) + byteArrayOf(0xE9.toByte(), 'u'.code.toByte(), 'm'.code.toByte(), 'b'.code.toByte()) + payload

        val ex = assertFailsWith<JumbfParseException> { JumbfParser.parse(bytes) }
        assertTrue(ex.reason.contains("non-ASCII"), "got: ${ex.reason}")
        assertTrue(ex.reason.contains("0xE9"), "reason should include the offending byte, got: ${ex.reason}")
    }

    // --- Synthetic JUMBF builders ----------------------------------------------------------------
    //
    // These helpers produce byte-exact JUMBF streams for tests. Every box header is 8 bytes:
    // LBox(4) + TBox(4). For `jumd` description boxes we prepend the payload with 16 zero bytes
    // (the content-type UUID, unused here) + a 1-byte toggle mask + optional null-terminated label.

    /** Build a generic length-prefixed box. [type] is the 4-char TBox; [content] is the payload. */
    private fun lengthPrefix(content: ByteArray, type: String): ByteArray {
        require(type.length == 4) { "TBox must be 4 chars, got '$type'" }
        val length = 8 + content.size
        return u32BE(length.toLong()) + type.encodeToByteArray() + content
    }

    /** Build a content box (`cbor`, `json`, `uuid`, …) carrying [payload]. */
    private fun contentBox(type: String, payload: ByteArray): ByteArray =
        lengthPrefix(payload, type)

    /** Build a `jumd` description box; if [label] is null, the toggle's LABEL bit is cleared. */
    private fun jumd(label: String?): ByteArray {
        val uuid = ByteArray(16) // unused for Phase 1
        val toggle: Byte = if (label != null) 0x03 else 0x00 // requestable + label present
        val labelBytes = if (label != null) {
            label.encodeToByteArray() + byteArrayOf(0) // null terminator
        } else {
            byteArrayOf()
        }
        val payload = uuid + byteArrayOf(toggle) + labelBytes
        return lengthPrefix(payload, "jumd")
    }

    /**
     * Build a full `jumb` superbox with a `jumd` description (labeled [label]) followed by the
     * provided [content] byte fragments (each fragment must already be a well-formed box).
     */
    private fun superbox(label: String?, content: List<ByteArray>): ByteArray {
        val body = jumd(label) + content.fold(byteArrayOf()) { acc, b -> acc + b }
        return lengthPrefix(body, "jumb")
    }

    private fun u32BE(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
