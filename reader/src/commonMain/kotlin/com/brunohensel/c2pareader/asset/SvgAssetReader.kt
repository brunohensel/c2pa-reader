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
 * 1. Walk the document top to bottom, skipping the lexical regions where a `<c2pa:manifest`
 *    string match would be a false positive — XML comments (`<!--…-->`), CDATA sections
 *    (`<![CDATA[…]]>`), processing instructions (`<?…?>`), end tags, and declarations — to
 *    find a real opening `<c2pa:manifest` tag.
 * 2. Skip the tag's attributes to reach the end of the opening tag.
 * 3. Slurp text up to the closing `</c2pa:manifest>` and base64-decode it.
 *
 * Anything structurally broken on the happy path — unterminated tags, truncated open tag,
 * invalid base64 — is surfaced as [MalformedAssetException]. "No `<c2pa:manifest>` element
 * at all" is a normal outcome and returns `null` so the orchestrator reports `NoManifest`.
 * If multiple matching `<c2pa:manifest>` elements are present, the first match wins; the
 * scanner does not validate uniqueness (matches c2pa-rs `svg_io.rs`).
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
     * Locates a real `<c2pa:manifest` opening tag. Returns the byte offset of the leading `<`,
     * or `-1` if no such tag exists anywhere in the document.
     *
     * The scanner walks the input in document order and steps past every lexical region where
     * a literal `<c2pa:manifest` string would be a false positive:
     *
     * - XML comments (`<!-- … -->`)
     * - CDATA sections (`<![CDATA[ … ]]>`)
     * - Processing instructions and XML declarations (`<? … ?>`)
     * - Any other tag's attribute list (so `>` inside a quoted attribute value is honored
     *   and a neighboring element named `<c2pa:manifest_other>` is not misidentified)
     * - End tags (`</…>`) and declarations (`<!DOCTYPE…>`, `<!ENTITY…>`)
     *
     * When it hits a `<c2pa:manifest` token whose next character is `>`, `/`, or whitespace,
     * that's a real opening tag and the function returns its offset.
     *
     * The one case where we intentionally surface corruption to the caller: a `<c2pa:manifest`
     * token that runs off the end of the buffer before the delimiter character is seen. We
     * return the hit offset so the caller's tag-end scanner throws `Malformed` rather than
     * swallowing a clearly truncated manifest tag as "no manifest".
     */
    private fun findOpenTag(text: String): Int {
        var i = 0
        while (i < text.length) {
            if (text[i] != '<') { i++; continue }
            when {
                startsWithAt(text, i, "<!--") -> {
                    val end = text.indexOf("-->", startIndex = i + 4)
                    if (end < 0) return -1
                    i = end + 3
                }
                startsWithAt(text, i, "<![CDATA[") -> {
                    val end = text.indexOf("]]>", startIndex = i + 9)
                    if (end < 0) return -1
                    i = end + 3
                }
                startsWithAt(text, i, "<?") -> {
                    val end = text.indexOf("?>", startIndex = i + 2)
                    if (end < 0) return -1
                    i = end + 2
                }
                startsWithAt(text, i, "<!") -> {
                    // Declarations like <!DOCTYPE …> / <!ENTITY …>. May contain quoted literals
                    // and an internal subset `[ … ]`; skip to the terminating `>` carefully.
                    val end = findDeclarationEnd(text, i + 2) ?: return -1
                    i = end + 1
                }
                startsWithAt(text, i, "</") -> {
                    val end = findOpenTagEnd(text, i + 2) ?: return -1
                    i = end + 1
                }
                startsWithAt(text, i, OPEN_TAG) -> {
                    val nextIdx = i + OPEN_TAG.length
                    if (nextIdx >= text.length) {
                        // Buffer ends mid-tag. Return the hit so findOpenTagEnd surfaces this
                        // as a Malformed truncation instead of misreporting NoManifest.
                        return i
                    }
                    val next = text[nextIdx]
                    if (next == '>' || next == '/' || next.isWhitespace()) return i
                    // e.g. `<c2pa:manifest_other>` — keep scanning past this tag's own open.
                    val end = findOpenTagEnd(text, i + 1) ?: return -1
                    i = end + 1
                }
                else -> {
                    // Any other start-of-tag: walk to its `>` so we don't re-scan its interior.
                    val end = findOpenTagEnd(text, i + 1) ?: return -1
                    i = end + 1
                }
            }
        }
        return -1
    }

    private fun startsWithAt(text: String, index: Int, prefix: String): Boolean {
        if (index + prefix.length > text.length) return false
        return text.regionMatches(index, prefix, 0, prefix.length)
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
     * Returns the index of the `>` that terminates a `<!…>` declaration starting at [from], or
     * null if it runs off the end. Quoted literals (`"…"` / `'…'`) and internal subset brackets
     * (`[…]`) are honored so a `>` inside either of those doesn't terminate the declaration
     * prematurely.
     */
    private fun findDeclarationEnd(text: String, from: Int): Int? {
        var i = from
        var quote: Char? = null
        var bracketDepth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote != null -> { /* inside quoted literal; keep consuming */ }
                c == '"' || c == '\'' -> quote = c
                c == '[' -> bracketDepth++
                c == ']' && bracketDepth > 0 -> bracketDepth--
                c == '>' && bracketDepth == 0 -> return i
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
