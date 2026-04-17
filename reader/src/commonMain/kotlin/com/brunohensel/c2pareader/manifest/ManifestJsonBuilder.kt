package com.brunohensel.c2pareader.manifest

import com.brunohensel.c2pareader.cbor.CborDecoder
import com.brunohensel.c2pareader.jumbf.JumbfContentBox
import com.brunohensel.c2pareader.jumbf.JumbfSuperbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Walks a parsed JUMBF manifest-store tree and emits a reader-schema-shaped [JsonObject].
 *
 * ## Reference implementation
 *
 * Ports the output layout produced by c2pa-rs's
 * [sdk/src/reader.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/reader.rs) and
 * [sdk/src/crjson.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/crjson.rs).
 *
 * ## Phase-1 coverage
 *
 * Produces the **content-derived** fields of the reader schema: `active_manifest`, `manifests`,
 * and per-manifest `{claim_generator, claim_generator_info, title, format, instance_id,
 * thumbnail, ingredients, assertions, label, claim_version}`.
 *
 * Fields that require either a full cryptographic validation pipeline or COSE+X.509 parsing
 * (`signature_info`, `validation_state`, `validation_results`, `validation_status`) are left
 * to later phases — they're stripped on both sides before golden comparison.
 */
internal object ManifestJsonBuilder {

    private const val MANIFEST_STORE_LABEL = "c2pa"
    private const val CLAIM_LABEL = "c2pa.claim"
    private const val ASSERTIONS_LABEL = "c2pa.assertions"
    private const val CBOR_CONTENT_TYPE = "cbor"
    private const val CLAIM_THUMBNAIL_LABEL_PREFIX = "c2pa.thumbnail.claim."

    /**
     * Top-level labels that live structurally in the manifest store but are NOT surfaced in
     * the reader-schema `assertions` array (they map to dedicated top-level fields or are
     * purely internal integrity data).
     */
    private val INTERNAL_ASSERTION_LABEL_PREFIXES = listOf(
        "c2pa.hash.",      // data/box integrity hashes, used for validation only
        "c2pa.thumbnail.", // surfaced via the manifest's `thumbnail` field
    )

    /**
     * Claim CBOR keys that the reader schema exposes under a different name, mostly because
     * the CBOR form uses XMP-style dotted namespaces (`dc:title`) whereas the JSON form is
     * plain snake_case.
     */
    private val CLAIM_KEY_RENAMES = mapOf(
        "dc:title" to "title",
        "dc:format" to "format",
        "instanceID" to "instance_id",
    )

    /**
     * Claim CBOR keys that are internal to the signing / verification pipeline and must not
     * leak into the reader-schema JSON (the claim's `assertions` and `ingredients` arrays are
     * hashed URI lists that the reader *resolves* into the corresponding top-level arrays,
     * so they don't appear as claim fields themselves).
     */
    private val CLAIM_KEYS_TO_DROP = setOf(
        "signature",    // internal JUMBF reference to c2pa.signature box
        "assertions",   // hashed URI list; resolved into the top-level `assertions` array
        "ingredients",  // hashed URI list; resolved into the top-level `ingredients` array
        "redactions",   // signing-time only
        "alg",          // claim signature algorithm hint
        "hash_alg",     // assertion hash algorithm hint
        "created_time", // signing timestamp; surfaced via signature_info in full reader
    )

    fun build(manifestStore: JumbfSuperbox): JsonObject {
        if (manifestStore.label != MANIFEST_STORE_LABEL) {
            throw ManifestBuildException(
                "top-level superbox label is '${manifestStore.label}', expected '$MANIFEST_STORE_LABEL'"
            )
        }

        val manifestBoxes = manifestStore.children.filterIsInstance<JumbfSuperbox>()
        if (manifestBoxes.isEmpty()) {
            throw ManifestBuildException("manifest store has no child manifests")
        }

        val manifests = LinkedHashMap<String, JsonObject>(manifestBoxes.size)
        for (box in manifestBoxes) {
            val urn = box.label
                ?: throw ManifestBuildException("child manifest superbox has no label")
            manifests[urn] = buildManifest(box)
        }

        // c2pa-rs convention: the last manifest in the store is the active one.
        val activeUrn = manifests.keys.last()

        return buildJsonObject {
            put("active_manifest", activeUrn)
            put("manifests", JsonObject(manifests))
        }
    }

    private fun buildManifest(manifestBox: JumbfSuperbox): JsonObject {
        val urn = manifestBox.label
            ?: throw ManifestBuildException("manifest superbox has no label")

        val claimFields = readClaimFields(manifestBox)
        val assertionsJson = readAssertions(manifestBox)
        val thumbnail = synthesizeThumbnail(manifestBox, urn)

        val entries = linkedMapOf<String, JsonElement>()
        // Claim-derived fields first, in the order we know c2pa-rs uses.
        for ((k, v) in claimFields) entries[k] = v
        // Reader-synthesized fields.
        if (thumbnail != null) entries["thumbnail"] = thumbnail
        entries.putIfAbsent("ingredients", JsonArray(emptyList()))
        entries["assertions"] = JsonArray(assertionsJson)
        entries["label"] = JsonPrimitive(urn)
        return JsonObject(entries)
    }

    /**
     * If the manifest carries a `c2pa.thumbnail.claim.<ext>` assertion, synthesize the
     * `thumbnail: { format, identifier }` field the reader schema expects. Identifier is
     * the absolute JUMBF URI; format is derived from the label suffix (`.jpeg`, `.png`, …).
     *
     * The raw thumbnail bytes live inside the assertion box's content; we don't inline them
     * in Phase 1 (base64 `data:` URIs are deferred to a follow-up).
     */
    private fun synthesizeThumbnail(manifestBox: JumbfSuperbox, manifestUrn: String): JsonObject? {
        val assertionsBox = manifestBox.childSuperboxByLabel(ASSERTIONS_LABEL) ?: return null
        val thumbnailBox = assertionsBox.children
            .filterIsInstance<JumbfSuperbox>()
            .firstOrNull { it.label?.startsWith(CLAIM_THUMBNAIL_LABEL_PREFIX) == true }
            ?: return null

        val label = thumbnailBox.label!!
        val extension = label.substringAfterLast('.').lowercase()
        val mimeType = when (extension) {
            "jpeg", "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
        val identifier = "self#jumbf=/c2pa/$manifestUrn/$ASSERTIONS_LABEL/$label"
        return buildJsonObject {
            put("format", mimeType)
            put("identifier", identifier)
        }
    }

    private fun readClaimFields(manifestBox: JumbfSuperbox): LinkedHashMap<String, JsonElement> {
        val claimBox = manifestBox.childSuperboxByLabel(CLAIM_LABEL)
            ?: throw ManifestBuildException("manifest '${manifestBox.label}' missing '$CLAIM_LABEL' box")

        val cborBytes = claimBox.contentByType(CBOR_CONTENT_TYPE)
            ?: throw ManifestBuildException("'$CLAIM_LABEL' box missing '$CBOR_CONTENT_TYPE' content")

        val claim = CborDecoder.decode(cborBytes) as? JsonObject
            ?: throw ManifestBuildException("'$CLAIM_LABEL' CBOR is not a map")

        val out = linkedMapOf<String, JsonElement>()
        for ((key, value) in claim) {
            if (key in CLAIM_KEYS_TO_DROP) continue
            val outKey = CLAIM_KEY_RENAMES[key] ?: key
            out[outKey] = value
        }
        return out
    }

    private fun readAssertions(manifestBox: JumbfSuperbox): List<JsonObject> {
        val assertionsBox = manifestBox.childSuperboxByLabel(ASSERTIONS_LABEL) ?: return emptyList()

        return assertionsBox.children
            .filterIsInstance<JumbfSuperbox>()
            .filter { sub ->
                val label = sub.label ?: return@filter false
                INTERNAL_ASSERTION_LABEL_PREFIXES.none { prefix -> label.startsWith(prefix) }
            }
            .map { sub -> buildAssertionJson(sub) }
    }

    private fun buildAssertionJson(assertionBox: JumbfSuperbox): JsonObject {
        val label = assertionBox.label
            ?: throw ManifestBuildException("assertion box has no label")
        val cborBytes = assertionBox.contentByType(CBOR_CONTENT_TYPE)
            ?: throw ManifestBuildException("assertion '$label' has no '$CBOR_CONTENT_TYPE' content")

        val data = CborDecoder.decode(cborBytes)
        return buildJsonObject {
            put("label", label)
            put("data", data)
        }
    }
}

internal class ManifestBuildException(val reason: String) : RuntimeException(reason)

private fun JumbfSuperbox.childSuperboxByLabel(label: String): JumbfSuperbox? =
    children.filterIsInstance<JumbfSuperbox>().firstOrNull { it.label == label }

private fun JumbfSuperbox.contentByType(type: String): ByteArray? =
    children.filterIsInstance<JumbfContentBox>().firstOrNull { it.type == type }?.payload
