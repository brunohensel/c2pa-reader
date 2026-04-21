package com.brunohensel.c2pareader.manifest

import com.brunohensel.c2pareader.jumbf.JumbfBox
import com.brunohensel.c2pareader.jumbf.JumbfContentBox
import com.brunohensel.c2pareader.jumbf.JumbfSuperbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestJsonBuilderTest {

    @Test
    fun singleManifestProducesExpectedTopLevelShape() {
        val urn = "urn:uuid:manifest-1"
        val claimCbor = TestCbor.map(
            "dc:title" to TestCbor.text("cat.jpg"),
            "dc:format" to TestCbor.text("image/jpeg"),
            "instanceID" to TestCbor.text("xmp:iid:abc"),
            "claim_generator" to TestCbor.text("TestApp"),
        )
        val assertionCbor = TestCbor.map(
            "actions" to TestCbor.array(
                TestCbor.map("action" to TestCbor.text("c2pa.created"))
            )
        )

        val tree = manifestStore(
            manifest(
                urn,
                claimBox(claimCbor),
                assertionsBox(
                    assertionBox("c2pa.actions.v2", assertionCbor)
                ),
            )
        )

        val json = ManifestJsonBuilder.build(tree)

        assertEquals(JsonPrimitive(urn), json["active_manifest"])
        val manifests = json["manifests"] as JsonObject
        assertEquals(setOf(urn), manifests.keys)

        val m = manifests[urn] as JsonObject
        assertEquals(JsonPrimitive("cat.jpg"), m["title"])
        assertEquals(JsonPrimitive("image/jpeg"), m["format"])
        assertEquals(JsonPrimitive("xmp:iid:abc"), m["instance_id"])
        assertEquals(JsonPrimitive("TestApp"), m["claim_generator"])
        assertEquals(JsonPrimitive(urn), m["label"])
        assertEquals(JsonArray(emptyList()), m["ingredients"])

        val assertions = m["assertions"] as JsonArray
        assertEquals(1, assertions.size)
        val a0 = assertions[0].jsonObject
        assertEquals(JsonPrimitive("c2pa.actions.v2"), a0["label"])
        val data = a0["data"]!!.jsonObject
        val firstAction = (data["actions"] as JsonArray)[0].jsonObject
        assertEquals(JsonPrimitive("c2pa.created"), firstAction["action"])
    }

    @Test
    fun thumbnailFieldIsPassedThrough() {
        val claimCbor = TestCbor.map(
            "dc:title" to TestCbor.text("x"),
            "thumbnail" to TestCbor.map(
                "format" to TestCbor.text("image/jpeg"),
                "identifier" to TestCbor.text("self#jumbf=c2pa.assertions/c2pa.thumbnail.claim.jpeg"),
            ),
        )
        val tree = manifestStore(manifest("urn:a", claimBox(claimCbor), assertionsBox()))
        val json = ManifestJsonBuilder.build(tree)
        val m = (json["manifests"] as JsonObject)["urn:a"] as JsonObject
        val thumb = m["thumbnail"] as JsonObject
        assertEquals(JsonPrimitive("image/jpeg"), thumb["format"])
        assertEquals(
            JsonPrimitive("self#jumbf=c2pa.assertions/c2pa.thumbnail.claim.jpeg"),
            thumb["identifier"]
        )
    }

    @Test
    fun internalAssertionsAreFilteredFromOutputArray() {
        val claimCbor = TestCbor.map("dc:title" to TestCbor.text("x"))
        val assertionCbor = TestCbor.map("k" to TestCbor.text("v"))

        val tree = manifestStore(
            manifest(
                "urn:a",
                claimBox(claimCbor),
                assertionsBox(
                    assertionBox("c2pa.actions.v2", assertionCbor),
                    assertionBox("c2pa.hash.data", assertionCbor),             // filtered
                    assertionBox("c2pa.thumbnail.claim.jpeg", assertionCbor),  // filtered
                    assertionBox("com.example.custom", assertionCbor),
                ),
            )
        )

        val json = ManifestJsonBuilder.build(tree)
        val m = (json["manifests"] as JsonObject)["urn:a"] as JsonObject
        val labels = (m["assertions"] as JsonArray).map { (it.jsonObject["label"] as JsonPrimitive).content }
        assertEquals(listOf("c2pa.actions.v2", "com.example.custom"), labels)
    }

    @Test
    fun binaryAssertionsAreSkippedInsteadOfFailingBuild() {
        val claimCbor = TestCbor.map("dc:title" to TestCbor.text("x"))
        val tree = manifestStore(
            manifest(
                "urn:a",
                claimBox(claimCbor),
                assertionsBox(
                    assertionBox("c2pa.actions.v2", TestCbor.map("k" to TestCbor.text("v"))),
                    JumbfSuperbox(
                        label = "c2pa.icon",
                        children = listOf(
                            JumbfContentBox("bfdb", "image/svg+xml".encodeToByteArray()),
                            JumbfContentBox("bidb", "<svg/>".encodeToByteArray()),
                        ),
                    ),
                ),
            )
        )

        val json = ManifestJsonBuilder.build(tree)
        val m = (json["manifests"] as JsonObject)["urn:a"] as JsonObject
        val labels = (m["assertions"] as JsonArray).map { (it.jsonObject["label"] as JsonPrimitive).content }

        assertEquals(listOf("c2pa.actions.v2"), labels)
    }

    @Test
    fun internalClaimKeysAreDropped() {
        val claimCbor = TestCbor.map(
            "dc:title" to TestCbor.text("keep"),
            "signature" to TestCbor.text("self#jumbf=c2pa.signature"), // drop
            "alg" to TestCbor.text("ps256"),                           // drop
            "hash_alg" to TestCbor.text("sha256"),                     // drop
            "assertions" to TestCbor.array(TestCbor.text("hashed-uri")), // drop
        )
        val tree = manifestStore(manifest("urn:a", claimBox(claimCbor), assertionsBox()))
        val json = ManifestJsonBuilder.build(tree)
        val m = (json["manifests"] as JsonObject)["urn:a"] as JsonObject

        assertEquals(JsonPrimitive("keep"), m["title"])
        assertTrue(m.keys.none { it in setOf("signature", "alg", "hash_alg") }, "found internal key: ${m.keys}")
        // `assertions` must be the resolved JSON array, not the hashed-URI claim field.
        assertEquals(JsonArray(emptyList()), m["assertions"])
    }

    @Test
    fun claimVersionFieldIsPassedThrough() {
        val claimCbor = TestCbor.map(
            "dc:title" to TestCbor.text("x"),
            "claim_version" to TestCbor.uint(2),
        )
        val tree = manifestStore(manifest("urn:a", claimBox(claimCbor), assertionsBox()))
        val json = ManifestJsonBuilder.build(tree)
        val m = (json["manifests"] as JsonObject)["urn:a"] as JsonObject
        assertEquals(JsonPrimitive(2L), m["claim_version"])
    }

    @Test
    fun lastManifestIsActive() {
        val claim = TestCbor.map("dc:title" to TestCbor.text("x"))
        val tree = manifestStore(
            manifest("urn:first", claimBox(claim), assertionsBox()),
            manifest("urn:second", claimBox(claim), assertionsBox()),
        )
        val json = ManifestJsonBuilder.build(tree)
        assertEquals(JsonPrimitive("urn:second"), json["active_manifest"])
    }

    @Test
    fun manifestWithoutClaimThrows() {
        val tree = manifestStore(manifest("urn:a", assertionsBox()))
        val ex = assertFailsWith<ManifestBuildException> { ManifestJsonBuilder.build(tree) }
        assertTrue(ex.reason.contains("c2pa.claim"), "got: ${ex.reason}")
    }

    @Test
    fun wrongTopLevelLabelThrows() {
        val tree = JumbfSuperbox(
            label = "not-c2pa",
            children = listOf(manifest("urn:a", claimBox(TestCbor.map()), assertionsBox())),
        )
        val ex = assertFailsWith<ManifestBuildException> { ManifestJsonBuilder.build(tree) }
        assertTrue(ex.reason.contains("expected 'c2pa'"), "got: ${ex.reason}")
    }

    // --- JumbfTree fabricators -------------------------------------------------------------------

    private fun manifestStore(vararg manifests: JumbfSuperbox): JumbfSuperbox =
        JumbfSuperbox(label = "c2pa", children = manifests.toList())

    private fun manifest(urn: String, vararg children: JumbfBox): JumbfSuperbox =
        JumbfSuperbox(label = urn, children = children.toList())

    private fun claimBox(cbor: ByteArray): JumbfSuperbox =
        JumbfSuperbox(label = "c2pa.claim", children = listOf(JumbfContentBox("cbor", cbor)))

    private fun assertionsBox(vararg children: JumbfSuperbox): JumbfSuperbox =
        JumbfSuperbox(label = "c2pa.assertions", children = children.toList())

    private fun assertionBox(label: String, cbor: ByteArray): JumbfSuperbox =
        JumbfSuperbox(label = label, children = listOf(JumbfContentBox("cbor", cbor)))

    // --- Tiny CBOR encoder for hand-built test payloads ------------------------------------------
    //
    // Covers the "immediate" forms of CBOR (short lengths and small ints) — anything larger isn't
    // needed by the tests. See RFC 8949 §3 for the initial-byte format.

    private object TestCbor {
        /** Map with ≤23 entries. Key must be a String; value must already be CBOR-encoded bytes. */
        fun map(vararg entries: Pair<String, ByteArray>): ByteArray {
            require(entries.size <= 23) { "TestCbor.map supports ≤23 entries, got ${entries.size}" }
            val header = byteArrayOf((0xA0 or entries.size).toByte()) // major 5 | count
            return header + entries.fold(byteArrayOf()) { acc, (k, v) -> acc + text(k) + v }
        }

        /** Array with ≤23 items. */
        fun array(vararg items: ByteArray): ByteArray {
            require(items.size <= 23) { "TestCbor.array supports ≤23 items, got ${items.size}" }
            val header = byteArrayOf((0x80 or items.size).toByte()) // major 4 | count
            return header + items.fold(byteArrayOf()) { acc, b -> acc + b }
        }

        /** UTF-8 text string. Supports arbitrary length via extended length encoding. */
        fun text(s: String): ByteArray {
            val bytes = s.encodeToByteArray()
            val n = bytes.size
            val header = when {
                n <= 23 -> byteArrayOf((0x60 or n).toByte())
                n <= 0xFF -> byteArrayOf(0x78.toByte(), n.toByte())
                n <= 0xFFFF -> byteArrayOf(0x79.toByte(), (n shr 8).toByte(), n.toByte())
                else -> error("TestCbor.text doesn't cover strings larger than 64 KB")
            }
            return header + bytes
        }

        /** Unsigned integer, supporting the CBOR "immediate" and 1/2/4/8-byte extended forms. */
        fun uint(n: Long): ByteArray {
            require(n >= 0) { "TestCbor.uint requires a non-negative value" }
            return when {
                n <= 23 -> byteArrayOf(n.toByte())
                n <= 0xFFL -> byteArrayOf(0x18.toByte(), n.toByte())
                n <= 0xFFFFL -> byteArrayOf(0x19.toByte(), (n shr 8).toByte(), n.toByte())
                else -> error("TestCbor.uint doesn't cover values above 65535")
            }
        }
    }
}
