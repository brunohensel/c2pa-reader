package com.brunohensel.c2pareader.asset

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Extracts the raw JUMBF manifest-store bytes from an SVG asset by locating a
 * `<c2pa:manifest>` element nested inside `<metadata>` and base64-decoding its text content.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin port of the read path in c2pa-rs:
 * [sdk/src/asset_handlers/svg_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/svg_io.rs).
 * The element path (`svg > metadata > c2pa:manifest`), namespace URI
 * (`http://c2pa.org/manifest`), and base64 encoding all come from that file.
 *
 * ## Expected XML shape
 *
 * ```xml
 * <svg xmlns="http://www.w3.org/2000/svg" ...>
 *   ...
 *   <metadata>
 *     <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">
 *       <!-- base64(JUMBF manifest store) -->
 *     </c2pa:manifest>
 *   </metadata>
 * </svg>
 * ```
 *
 * ## Why a lightweight scanner instead of a DOM parser
 *
 * `:c2pareader`'s runtime-dependency set is exactly `kotlinx.serialization.json` (see PRD —
 * issue #1). Pulling in kotlinx.xml or any DOM parser would break that invariant and expand
 * the binary footprint on every platform target. The scanner has three jobs:
 *
 * 1. Find the opening `<c2pa:manifest` tag (skipping comments, CDATA, and any content that
 *    shows up before the element — but not validating the full XML grammar).
 * 2. Skip the tag's attributes to reach the end of the opening tag.
 * 3. Slurp text up to the closing `</c2pa:manifest>` and base64-decode it.
 *
 * Anything outside that happy path — unterminated tags, invalid base64, the element present
 * twice — is surfaced as [MalformedAssetException]. "No `<c2pa:manifest>` element at all" is
 * a normal outcome and returns `null` so the orchestrator reports `NoManifest`.
 *
 * ## Tolerance scope
 *
 * The PRD requires tolerance for "reasonable whitespace and attribute-order variation".
 * Concretely: any whitespace between attributes, attribute quotes (`'` or `"`), extra
 * whitespace inside the text, and `\r\n` line endings are all accepted. Namespace prefix
 * renaming (`<ca:manifest xmlns:ca="http://c2pa.org/manifest">`) is NOT supported — matches
 * the reference implementation, which hard-codes the `c2pa:` prefix.
 */
internal object SvgAssetReader : AssetReader {

    private const val OPEN_TAG: String = "<c2pa:manifest"
    private const val CLOSE_TAG: String = "</c2pa:manifest>"

    @OptIn(ExperimentalEncodingApi::class)
    override fun extractJumbf(bytes: ByteArray): ByteArray? {
        // SVG is text (UTF-8 by XML default; we allow a UTF-8 BOM by skipping it via decodeToString).
        // The scanner operates on the decoded string so byte-level vs. character-level offsets
        // don't diverge for multi-byte UTF-8 sequences inside `<metadata>` siblings.
        val text = bytes.decodeToString()

        val openStart = findOpenTag(text)
        if (openStart < 0) return null
        val attrsEnd = findOpenTagEnd(text, openStart + OPEN_TAG.length)
            ?: throw MalformedAssetException("unterminated <c2pa:manifest> tag")

        // Self-closing form `<c2pa:manifest ... />` carries no manifest bytes — surface as NoManifest.
        if (text[attrsEnd - 1] == '/') return null

        val contentStart = attrsEnd + 1 // past '>'
        val closeStart = text.indexOf(CLOSE_TAG, startIndex = contentStart)
        if (closeStart < 0) {
            throw MalformedAssetException("missing </c2pa:manifest> close tag")
        }

        val rawContent = text.substring(contentStart, closeStart)
        val stripped = stripWhitespace(rawContent)
        if (stripped.isEmpty()) return null

        return try {
            Base64.decode(stripped)
        } catch (e: IllegalArgumentException) {
            throw MalformedAssetException("<c2pa:manifest> content is not valid base64: ${e.message}")
        }
    }

    /**
     * Locates the `<c2pa:manifest` element. Accepts only an opening tag whose next character
     * is whitespace, `>`, or `/` — so `<c2pa:manifest_other>` is not misidentified as the
     * manifest element.
     */
    private fun findOpenTag(text: String): Int {
        var searchFrom = 0
        while (true) {
            val hit = text.indexOf(OPEN_TAG, startIndex = searchFrom)
            if (hit < 0) return -1
            val nextIdx = hit + OPEN_TAG.length
            if (nextIdx >= text.length) return -1
            val next = text[nextIdx]
            if (next == '>' || next == '/' || next.isWhitespace()) return hit
            searchFrom = nextIdx
        }
    }

    /**
     * Returns the index of the `>` that terminates the opening tag starting at [from], or null
     * if the tag runs off the end. Skips `>` characters embedded inside single- or double-quoted
     * attribute values (XML 1.0 attributes can't contain the opposite quote, but `>` inside the
     * value is legal and must not be confused with the tag close).
     */
    private fun findOpenTagEnd(text: String, from: Int): Int? {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote != null -> { /* inside attribute value; keep consuming */ }
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return null
    }

    /**
     * Strips ASCII whitespace from [s]. Base64 tolerates whitespace in many implementations but
     * the Kotlin stdlib decoder in strict mode does not, and we want to forgive the typical
     * pretty-printed XML shape (newlines + indentation around the payload).
     */
    private fun stripWhitespace(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (!c.isWhitespace()) sb.append(c)
        return sb.toString()
    }
}
