package com.brunohensel.c2pareader.jumbf

/**
 * Internal typed representation of the JUMBF box tree produced by [JumbfParser].
 *
 * Kept deliberately minimal: callers of [JumbfParser] only need to walk the tree to
 * build the reader-schema JSON. They do not need to re-serialize JUMBF or preserve
 * every header field.
 */
internal sealed class JumbfBox {
    abstract val type: String
}

/**
 * A JUMBF superbox (`jumb`). Its label is lifted from the superbox's mandatory
 * description (`jumd`) child — the `jumd` itself is *not* exposed as a child, so
 * [children] lists only content boxes and nested superboxes.
 */
internal data class JumbfSuperbox(
    override val type: String = "jumb",
    val label: String?,
    val children: List<JumbfBox>,
) : JumbfBox()

/**
 * A JUMBF content box (`cbor`, `json`, `uuid`, `bidb`, …). [payload] is the raw bytes
 * between the box header and the box end, exactly as read from the wire.
 *
 * Not a `data class` on purpose: Kotlin's auto-generated `equals` on a data class
 * uses reference equality for `ByteArray` fields, so two content boxes holding the
 * same bytes would compare unequal (surprising in tests). Overriding `equals` and
 * `hashCode` manually lets us delegate to `ByteArray.contentEquals` / `contentHashCode`,
 * which is the structural comparison callers actually expect.
 */
internal class JumbfContentBox(
    override val type: String,
    val payload: ByteArray,
) : JumbfBox() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JumbfContentBox) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    override fun toString(): String = "JumbfContentBox(type='$type', payload=${payload.size} bytes)"
}

internal class JumbfParseException(val reason: String) : RuntimeException(reason)
