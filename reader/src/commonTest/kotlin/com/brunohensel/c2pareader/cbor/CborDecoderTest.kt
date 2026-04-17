package com.brunohensel.c2pareader.cbor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests driven by RFC 8949 Appendix A vectors plus C2PA-shaped assertion payloads.
 * Every test name names the wire-level input, because the payload is what matters —
 * the Kotlin value is the derived artifact.
 */
class CborDecoderTest {

    // --- Unsigned integers (major type 0) --------------------------------------------------------

    @Test fun uint0_00() = assertDecodes("00", JsonPrimitive(0L))
    @Test fun uint1_01() = assertDecodes("01", JsonPrimitive(1L))
    @Test fun uint23_17() = assertDecodes("17", JsonPrimitive(23L))
    @Test fun uint24_1818() = assertDecodes("1818", JsonPrimitive(24L))
    @Test fun uint100_1864() = assertDecodes("1864", JsonPrimitive(100L))
    @Test fun uint1000_1903e8() = assertDecodes("1903e8", JsonPrimitive(1000L))
    @Test fun uint1000000_1a000f4240() = assertDecodes("1a000f4240", JsonPrimitive(1_000_000L))
    @Test fun uint1e12_1b000000e8d4a51000() =
        assertDecodes("1b000000e8d4a51000", JsonPrimitive(1_000_000_000_000L))

    @Test
    fun uintBeyondLongMaxThrows() {
        val bytes = hex("1bffffffffffffffff") // 2^64 - 1
        val ex = assertFailsWith<CborDecodeException> { CborDecoder.decode(bytes) }
        assertTrue(ex.reason.contains("Long.MAX_VALUE"), "got: ${ex.reason}")
    }

    // --- Negative integers (major type 1) --------------------------------------------------------

    @Test fun negOne_20() = assertDecodes("20", JsonPrimitive(-1L))
    @Test fun negTen_29() = assertDecodes("29", JsonPrimitive(-10L))
    @Test fun negHundred_3863() = assertDecodes("3863", JsonPrimitive(-100L))
    @Test fun negThousand_3903e7() = assertDecodes("3903e7", JsonPrimitive(-1000L))

    // --- Byte strings (major type 2) → base64 JSON string --------------------------------------

    @Test fun emptyByteString_40() = assertDecodes("40", JsonPrimitive(""))

    @Test
    fun bytes01020304_4401020304() {
        // 4 raw bytes → base64 "AQIDBA==". Base64 packs 3 bytes into 4 chars; 4 bytes need
        // 6 output chars plus 2 `=` padding chars to round the block up to a multiple of 4.
        assertDecodes("4401020304", JsonPrimitive("AQIDBA=="))
    }

    // --- Text strings (major type 3) -------------------------------------------------------------

    @Test fun emptyText_60() = assertDecodes("60", JsonPrimitive(""))
    @Test fun textA_6161() = assertDecodes("6161", JsonPrimitive("a"))
    @Test fun textIETF_6449455446() = assertDecodes("6449455446", JsonPrimitive("IETF"))
    @Test fun textQuoteBackslash_62225c() = assertDecodes("62225c", JsonPrimitive("\"\\"))

    // --- Arrays (major type 4) -------------------------------------------------------------------

    @Test fun emptyArray_80() = assertDecodes("80", JsonArray(emptyList()))

    @Test
    fun array_1_2_3_83010203() {
        assertDecodes(
            "83010203",
            JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(2L), JsonPrimitive(3L)))
        )
    }

    // --- Maps (major type 5) ---------------------------------------------------------------------

    @Test fun emptyMap_a0() = assertDecodes("a0", JsonObject(emptyMap()))

    @Test
    fun mapAbBNested_a261610161628203() {
        // {"a": 1, "b": [2, 3]}
        assertDecodes(
            "a26161016162820203",
            JsonObject(
                mapOf(
                    "a" to JsonPrimitive(1L),
                    "b" to JsonArray(listOf(JsonPrimitive(2L), JsonPrimitive(3L)))
                )
            )
        )
    }

    @Test
    fun nonStringMapKeyIsRejected() {
        // {1: 2} — integer key, not supported for JSON output.
        val bytes = hex("a10102")
        val ex = assertFailsWith<CborDecodeException> { CborDecoder.decode(bytes) }
        assertTrue(ex.reason.contains("text-string keys"), "got: ${ex.reason}")
    }

    // --- Tagged items (major type 6) → transparent passthrough ---------------------------------

    @Test
    fun tag0DateTimeUnwrapsToInnerString() {
        // 0("2013-03-21T20:04:00Z") — standard RFC 3339 datetime tag
        assertDecodes(
            "c074323031332d30332d32315432303a30343a30305a",
            JsonPrimitive("2013-03-21T20:04:00Z")
        )
    }

    // --- Simple values (major type 7) ------------------------------------------------------------

    @Test fun falseConstant_f4() = assertDecodes("f4", JsonPrimitive(false))
    @Test fun trueConstant_f5() = assertDecodes("f5", JsonPrimitive(true))
    @Test fun nullConstant_f6() = assertDecodes("f6", JsonNull)
    @Test fun undefinedAsNull_f7() = assertDecodes("f7", JsonNull)

    // --- Floats ----------------------------------------------------------------------------------

    @Test
    fun float64_1_1_fb3ff199999999999a() {
        assertDecodes("fb3ff199999999999a", JsonPrimitive(1.1))
    }

    @Test
    fun float32_100000_fa47c35000() {
        assertDecodes("fa47c35000", JsonPrimitive(100000.0))
    }

    @Test
    fun float16_1_5_f93e00() {
        assertDecodes("f93e00", JsonPrimitive(1.5))
    }

    // --- Indefinite-length -----------------------------------------------------------------------

    @Test
    fun indefiniteArrayOfTwoIntegers() {
        // 9f = array(*) ; 01 02 = items ; ff = break marker ; decodes to [1, 2]
        assertDecodes("9f0102ff", JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(2L))))
    }

    @Test
    fun indefiniteMap() {
        // bf = map(*) ; 61 61 01 = "a": 1 ; 61 62 02 = "b": 2 ; ff = break
        assertDecodes(
            "bf616101616202ff",
            JsonObject(mapOf("a" to JsonPrimitive(1L), "b" to JsonPrimitive(2L)))
        )
    }

    @Test
    fun indefiniteTextString() {
        // 7f = text(*) ; 65 "strea" ; 64 "ming" ; ff = break ; concatenated → "streaming"
        assertDecodes("7f657374726561646d696e67ff", JsonPrimitive("streaming"))
    }

    // --- Truncated / malformed -------------------------------------------------------------------

    @Test
    fun truncatedInputThrows() {
        // 1a says "4-byte uint follows" but only 3 bytes remain.
        val ex = assertFailsWith<CborDecodeException> { CborDecoder.decode(hex("1a000f42")) }
        assertTrue(ex.reason.contains("unexpected end"), "got: ${ex.reason}")
    }

    @Test
    fun trailingBytesThrow() {
        // Two consecutive valid values — should error because decoder expects exactly one.
        val ex = assertFailsWith<CborDecodeException> { CborDecoder.decode(hex("0100")) }
        assertTrue(ex.reason.contains("trailing"), "got: ${ex.reason}")
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun assertDecodes(hexInput: String, expected: JsonElement) {
        assertEquals(expected, CborDecoder.decode(hex(hexInput)))
    }

    /**
     * Convert a hex string like `"1a000f4240"` to the raw `ByteArray` `[0x1a, 0x00, 0x0f, 0x42, 0x40]`.
     *
     * **Why pairs of characters**: one byte = 8 bits. Hex is base-16, so each character carries
     * 4 bits (a "nibble"). Two hex characters together encode exactly one byte — the first is
     * the high nibble (bits 7..4), the second is the low nibble (bits 3..0). That's why the
     * input length must be even.
     *
     * **How the combination works**:
     * - `digitToInt(16)` parses a single hex char into its 0..15 integer value.
     * - `shl 4` (shift left by 4) moves the high nibble into bits 7..4 of the result.
     *   For example, `0xA shl 4` = `0xA0`.
     * - `or lo` merges the low nibble into bits 3..0. Since `shl 4` left those bits as zero,
     *   bitwise OR is equivalent to addition here.
     * - `.toByte()` reinterprets the 0..255 `Int` as Kotlin's signed `Byte` (-128..127).
     *   The underlying 8 bits are unchanged; only the Kotlin type label differs.
     */
    private fun hex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex string must have even length, got ${clean.length}" }
        return ByteArray(clean.length / 2) { i ->
            val hi = clean[i * 2].digitToInt(16)
            val lo = clean[i * 2 + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
