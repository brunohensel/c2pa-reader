package com.brunohensel.c2pareader.cbor

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure-Kotlin CBOR → [JsonElement] decoder covering the [RFC 8949](https://www.rfc-editor.org/rfc/rfc8949)
 * subset used by C2PA assertion payloads and claim data. Output plugs directly into
 * [kotlinx.serialization.json] data structures, keeping the downstream ManifestJsonBuilder
 * in one JSON-native model.
 *
 * ## Reference implementation
 *
 * Ports the read path that c2pa-rs exercises inside its assertion handlers. The upstream
 * CBOR library is `ciborium`, which the c2pa-rs SDK consumes — structural decisions here
 * mirror that lib's defaults (e.g. tagged items unwrap to their inner value, byte strings
 * become base64 text).
 *
 * ## CBOR wire format primer
 *
 * Every CBOR item starts with a single **initial byte** whose top 3 bits select the
 * **major type** (0–7) and whose low 5 bits carry an **additional info** number (0–31).
 * The additional info either gives the value directly (0–23), signals how many following
 * bytes carry the value (24→1, 25→2, 26→4, 27→8), or flags a special case (31 = indefinite
 * length for major types 2/3/4/5, or a break marker in major type 7).
 *
 * ```
 *  7 6 5  4 3 2 1 0
 * +-----+-----------+
 * | MT  | addInfo   |
 * +-----+-----------+
 * ```
 *
 * Major types:
 * - 0: unsigned integer — value stored in addInfo / extended bytes
 * - 1: negative integer — stored as `n`; decodes to `-1 - n`
 * - 2: byte string — length then raw bytes; we emit base64 JSON text
 * - 3: text string — length then UTF-8 bytes; we emit JSON string
 * - 4: array — element count then N items
 * - 5: map — pair count then N (key, value) items
 * - 6: tag — a tag number then one tagged item; we pass the inner value through unchanged
 * - 7: simple values (false/true/null), floats, break marker
 */
internal object CborDecoder {

    fun decode(bytes: ByteArray): JsonElement {
        val reader = ByteReader(bytes)
        val value = readValue(reader)
        if (!reader.isEof) {
            throw CborDecodeException("unexpected ${reader.remaining} trailing byte(s) after decoded value")
        }
        return value
    }

    private fun readValue(r: ByteReader): JsonElement {
        val initial = r.readUByte()
        val majorType = (initial shr 5) and 0x07
        val addInfo = initial and 0x1F
        return when (majorType) {
            MT_UNSIGNED_INT -> JsonPrimitive(readUInt(r, addInfo))
            MT_NEGATIVE_INT -> JsonPrimitive(-1L - readUInt(r, addInfo))
            MT_BYTE_STRING -> JsonPrimitive(base64(readByteString(r, addInfo)))
            MT_TEXT_STRING -> JsonPrimitive(readTextString(r, addInfo))
            MT_ARRAY -> readArray(r, addInfo)
            MT_MAP -> readMap(r, addInfo)
            MT_TAG -> {
                readUInt(r, addInfo) // tag number, currently discarded (transparent passthrough)
                readValue(r)
            }
            MT_SIMPLE_FLOAT -> readSimpleOrFloat(r, addInfo)
            else -> throw CborDecodeException("unreachable major type $majorType")
        }
    }

    /**
     * Reads an unsigned integer whose value is expressed via [addInfo] alone (0..23) or via
     * 1/2/4/8 following big-endian bytes (addInfo 24..27). A value larger than [Long.MAX_VALUE]
     * cannot be represented in a Kotlin `Long`; we reject it rather than silently overflow.
     */
    private fun readUInt(r: ByteReader, addInfo: Int): Long = when (addInfo) {
        in 0..23 -> addInfo.toLong()
        ADD_INFO_1_BYTE -> r.readUByte().toLong()
        ADD_INFO_2_BYTES -> r.readUShort().toLong()
        ADD_INFO_4_BYTES -> r.readUInt32()
        ADD_INFO_8_BYTES -> {
            val v = r.readUInt64()
            if (v < 0) throw CborDecodeException("unsigned integer exceeds Long.MAX_VALUE")
            v
        }
        else -> throw CborDecodeException(
            "reserved/indefinite addInfo $addInfo not valid for integer/length"
        )
    }

    private fun readByteString(r: ByteReader, addInfo: Int): ByteArray {
        if (addInfo == ADD_INFO_INDEFINITE) {
            return concatChunks(r, expectedMajorType = MT_BYTE_STRING)
        }
        val len = readUInt(r, addInfo).toIntOrThrow("byte-string length")
        return r.readBytes(len)
    }

    private fun readTextString(r: ByteReader, addInfo: Int): String {
        if (addInfo == ADD_INFO_INDEFINITE) {
            return concatChunks(r, expectedMajorType = MT_TEXT_STRING).decodeToString()
        }
        val len = readUInt(r, addInfo).toIntOrThrow("text-string length")
        return r.readBytes(len).decodeToString()
    }

    /**
     * Indefinite-length strings are encoded as a sequence of definite-length chunks of the
     * same major type, terminated by the break marker (0xFF). Each chunk's bytes are
     * concatenated to form the final value. See RFC 8949 §3.2.3.
     */
    private fun concatChunks(r: ByteReader, expectedMajorType: Int): ByteArray {
        val buffer = ArrayList<Byte>()
        while (true) {
            val initial = r.readUByte()
            if (initial == BREAK_MARKER) break
            val mt = (initial shr 5) and 0x07
            val addInfo = initial and 0x1F
            if (mt != expectedMajorType) {
                throw CborDecodeException(
                    "indefinite-length chunk has wrong major type $mt (expected $expectedMajorType)"
                )
            }
            if (addInfo == ADD_INFO_INDEFINITE) {
                throw CborDecodeException("nested indefinite-length chunk is not permitted")
            }
            val len = readUInt(r, addInfo).toIntOrThrow("chunk length")
            val chunk = r.readBytes(len)
            for (b in chunk) buffer.add(b)
        }
        return buffer.toByteArray()
    }

    private fun readArray(r: ByteReader, addInfo: Int): JsonArray {
        if (addInfo == ADD_INFO_INDEFINITE) {
            val items = mutableListOf<JsonElement>()
            while (true) {
                if (r.peekUByte() == BREAK_MARKER) {
                    r.readUByte() // consume break
                    break
                }
                items.add(readValue(r))
            }
            return JsonArray(items)
        }
        val count = readUInt(r, addInfo).toIntOrThrow("array length")
        val items = ArrayList<JsonElement>(count)
        repeat(count) { items.add(readValue(r)) }
        return JsonArray(items)
    }

    private fun readMap(r: ByteReader, addInfo: Int): JsonObject {
        if (addInfo == ADD_INFO_INDEFINITE) {
            val entries = linkedMapOf<String, JsonElement>()
            while (true) {
                if (r.peekUByte() == BREAK_MARKER) {
                    r.readUByte() // consume break
                    break
                }
                val key = readMapKey(r)
                entries[key] = readValue(r)
            }
            return JsonObject(entries)
        }
        val count = readUInt(r, addInfo).toIntOrThrow("map entry count")
        val entries = LinkedHashMap<String, JsonElement>(count)
        repeat(count) {
            val key = readMapKey(r)
            entries[key] = readValue(r)
        }
        return JsonObject(entries)
    }

    /**
     * JSON objects only allow string keys, so we require CBOR map keys to be text strings.
     * Non-string keys appear in some CBOR profiles (COSE, for example), but C2PA assertion
     * payloads that reach this decoder use string-keyed maps.
     */
    private fun readMapKey(r: ByteReader): String {
        val initial = r.readUByte()
        val mt = (initial shr 5) and 0x07
        val addInfo = initial and 0x1F
        if (mt != MT_TEXT_STRING) {
            throw CborDecodeException(
                "map key has major type $mt; only text-string keys (major 3) are supported"
            )
        }
        return readTextString(r, addInfo)
    }

    private fun readSimpleOrFloat(r: ByteReader, addInfo: Int): JsonElement = when (addInfo) {
        SIMPLE_FALSE -> JsonPrimitive(false)
        SIMPLE_TRUE -> JsonPrimitive(true)
        SIMPLE_NULL -> JsonNull
        SIMPLE_UNDEFINED -> JsonNull // closest JSON analogue
        ADD_INFO_1_BYTE -> {
            // 1-byte simple value (range 32..255). C2PA doesn't use these; reject.
            val sv = r.readUByte()
            throw CborDecodeException("unsupported simple value $sv")
        }
        FLOAT_16 -> JsonPrimitive(halfFloatToDouble(r.readUShort()))
        FLOAT_32 -> JsonPrimitive(Float.fromBits(r.readUInt32().toInt()).toDouble())
        FLOAT_64 -> JsonPrimitive(Double.fromBits(r.readUInt64()))
        else -> throw CborDecodeException("reserved simple/float code $addInfo")
    }

    /** IEEE-754 half-precision (binary16) → double. RFC 8949 §3.2.1 reference algorithm. */
    private fun halfFloatToDouble(bits: Int): Double {
        val sign = (bits shr 15) and 0x1
        val exp = (bits shr 10) and 0x1F
        val mant = bits and 0x3FF
        val value: Double = when (exp) {
            0 -> if (mant == 0) 0.0 else mant * 2.0.pow(-24) // subnormal
            0x1F -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (mant + 1024) * 2.0.pow(exp - 25)
        }
        return if (sign == 1) -value else value
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64(bytes: ByteArray): String = Base64.encode(bytes)

    private fun Long.toIntOrThrow(context: String): Int {
        if (this < 0 || this > Int.MAX_VALUE) {
            throw CborDecodeException("$context $this out of Int range")
        }
        return this.toInt()
    }

    // --- Major types (top 3 bits of initial byte) ------------------------------------------------
    private const val MT_UNSIGNED_INT = 0
    private const val MT_NEGATIVE_INT = 1
    private const val MT_BYTE_STRING = 2
    private const val MT_TEXT_STRING = 3
    private const val MT_ARRAY = 4
    private const val MT_MAP = 5
    private const val MT_TAG = 6
    private const val MT_SIMPLE_FLOAT = 7

    // --- Additional-info decoding ----------------------------------------------------------------
    private const val ADD_INFO_1_BYTE = 24
    private const val ADD_INFO_2_BYTES = 25
    private const val ADD_INFO_4_BYTES = 26
    private const val ADD_INFO_8_BYTES = 27
    private const val ADD_INFO_INDEFINITE = 31

    // --- Major type 7 codes ----------------------------------------------------------------------
    private const val SIMPLE_FALSE = 20
    private const val SIMPLE_TRUE = 21
    private const val SIMPLE_NULL = 22
    private const val SIMPLE_UNDEFINED = 23
    private const val FLOAT_16 = 25
    private const val FLOAT_32 = 26
    private const val FLOAT_64 = 27
    private const val BREAK_MARKER = 0xFF
}

internal class CborDecodeException(val reason: String) : RuntimeException(reason)

/** Cursor over a byte array; every read advances the position. */
private class ByteReader(private val bytes: ByteArray) {
    private var pos: Int = 0

    val isEof: Boolean get() = pos >= bytes.size
    val remaining: Int get() = bytes.size - pos

    fun readUByte(): Int {
        if (pos >= bytes.size) throw CborDecodeException("unexpected end of CBOR input")
        val v = bytes[pos].toInt() and 0xFF
        pos++
        return v
    }

    fun peekUByte(): Int {
        if (pos >= bytes.size) throw CborDecodeException("unexpected end of CBOR input")
        return bytes[pos].toInt() and 0xFF
    }

    fun readUShort(): Int {
        if (pos + 2 > bytes.size) throw CborDecodeException("unexpected end of CBOR input")
        val v = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun readUInt32(): Long {
        if (pos + 4 > bytes.size) throw CborDecodeException("unexpected end of CBOR input")
        val v = ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 8) or
            (bytes[pos + 3].toLong() and 0xFF)
        pos += 4
        return v
    }

    /**
     * Reads 8 bytes as a big-endian unsigned 64-bit value, returned as a `Long`.
     * Values ≥ 2^63 overflow into negative numbers; callers that need to reject such
     * values should check for `< 0`.
     */
    fun readUInt64(): Long {
        if (pos + 8 > bytes.size) throw CborDecodeException("unexpected end of CBOR input")
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    fun readBytes(n: Int): ByteArray {
        if (n < 0) throw CborDecodeException("negative length $n")
        if (pos + n > bytes.size) throw CborDecodeException("truncated input: needed $n bytes, have $remaining")
        val result = bytes.copyOfRange(pos, pos + n)
        pos += n
        return result
    }
}
