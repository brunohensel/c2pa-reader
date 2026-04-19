package com.brunohensel.c2pareader.asset

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class SvgAssetReaderTest {

    @Test
    fun svgWithC2paManifestElementReturnsDecodedBytes() {
        // Minimal C2PA-bearing SVG: <svg><metadata><c2pa:manifest>…base64…</c2pa:manifest>…</svg>.
        // The reader must locate the element, strip surrounding whitespace, and base64-decode
        // the text into the original JUMBF bytes.
        val jumbf = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val encoded = Base64.encode(jumbf)
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">$encoded</c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertContentEquals(jumbf, SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun svgWithoutManifestElementReturnsNull() {
        // Well-formed SVG with no manifest element → NoManifest (returns null from reader).
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>""".encodeToByteArray()
        assertNull(SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun whitespaceInBase64ContentIsTolerated() {
        // Pretty-printed SVG puts newlines and indentation around the base64 payload. The
        // strict Kotlin Base64 decoder rejects whitespace, so the reader must strip it first.
        val jumbf = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val encoded = Base64.encode(jumbf)
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">
                  $encoded
                </c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertContentEquals(jumbf, SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun attributeOrderVariationIsTolerated() {
        // Real-world SVG writers shuffle attributes; the reader must not require a specific
        // order on the `<c2pa:manifest>` tag.
        val jumbf = byteArrayOf(0x42, 0x43)
        val encoded = Base64.encode(jumbf)
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest
                    id="m1"
                    xmlns:c2pa="http://c2pa.org/manifest"
                    data-extra="ignored"
                >$encoded</c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertContentEquals(jumbf, SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun selfClosingManifestTagReturnsNull() {
        // <c2pa:manifest ... /> — syntactically legal but carries no bytes. Treat as no manifest.
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest"/>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertNull(SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun prefixCollisionIsNotMisidentified() {
        // An element whose name *starts with* `c2pa:manifest` but isn't the manifest element
        // (e.g. `<c2pa:manifest_foo>`) must not be matched.
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest_other xmlns:c2pa="http://c2pa.org/manifest">ignored</c2pa:manifest_other>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertNull(SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun unterminatedManifestOpenTagThrowsMalformed() {
        // Opening tag `<c2pa:manifest` never reaches `>` — structural corruption above the
        // JUMBF layer.
        val svg = """<svg><metadata><c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest" """
            .encodeToByteArray()
        assertFailsWith<MalformedAssetException> { SvgAssetReader.extractJumbf(svg) }
    }

    @Test
    fun missingCloseTagThrowsMalformed() {
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">abcd
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertFailsWith<MalformedAssetException> { SvgAssetReader.extractJumbf(svg) }
    }

    @Test
    fun invalidBase64PayloadThrowsMalformed() {
        // Base64 decoder strict mode rejects characters outside the alphabet. Surface as Malformed.
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">not@@base64!!</c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertFailsWith<MalformedAssetException> { SvgAssetReader.extractJumbf(svg) }
    }

    @Test
    fun manifestStringInsideCommentIsIgnored() {
        // The literal substring `<c2pa:manifest` appears inside an XML comment but no real
        // manifest element exists. A naive indexOf-based scanner would match the comment text
        // and then throw Malformed on the missing close tag; the tokenizing scanner must skip
        // comment regions entirely and return null (NoManifest).
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <!-- placeholder for <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest"> -->
              <rect/>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertNull(SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun manifestStringInsideCdataIsIgnored() {
        // Same concern as comments but for `<![CDATA[ … ]]>`. Must be skipped without false match.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <metadata>
                <![CDATA[<c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest">ignored</c2pa:manifest>]]>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertNull(SvgAssetReader.extractJumbf(svg))
    }

    @Test
    fun fileEndingExactlyOnOpenTagThrowsMalformed() {
        // Buffer ends exactly with `<c2pa:manifest` — no delimiter char follows. A naive
        // "next char must be whitespace/>//" check would return -1 (NoManifest) and silently
        // drop a clearly truncated manifest tag; the scanner must surface this as Malformed.
        val svg = """<svg><metadata><c2pa:manifest""".encodeToByteArray()
        assertFailsWith<MalformedAssetException> { SvgAssetReader.extractJumbf(svg) }
    }

    @Test
    fun attributeValueContainingGreaterThanIsSkipped() {
        // XML 1.0 allows `>` inside attribute values. The tag-end scanner must respect quotes so
        // it doesn't prematurely think the opening tag ended at the attribute-embedded `>`.
        val jumbf = byteArrayOf(0x01, 0x02)
        val encoded = Base64.encode(jumbf)
        val svg = """
            <svg>
              <metadata>
                <c2pa:manifest xmlns:c2pa="http://c2pa.org/manifest" data-gt="a>b">$encoded</c2pa:manifest>
              </metadata>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertContentEquals(jumbf, SvgAssetReader.extractJumbf(svg))
    }
}
