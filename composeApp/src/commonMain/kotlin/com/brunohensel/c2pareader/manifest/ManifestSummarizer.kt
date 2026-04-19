package com.brunohensel.c2pareader.manifest

import com.brunohensel.c2pareader.C2paResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts a [ManifestSummary] from the reader's JSON output for display in the gallery UI.
 *
 * Uses kotlinx.serialization typed data classes with label-dispatched polymorphism on
 * assertions: the per-assertion `data` shape depends on `label`, so a
 * [JsonContentPolymorphicSerializer] picks the right [Assertion] subtype by inspecting `label`
 * before decoding. `softwareAgent` (either a String or an `{name}` Object in the wild) is
 * normalized to a plain String via a dedicated serializer.
 *
 * Every manifest is walked (not just `active_manifest`) so AI signals carried by child manifests
 * still surface when the active one only has `c2pa.opened`.
 */
fun summarize(result: C2paResult): ManifestSummary? {
    val success = result as? C2paResult.Success ?: return null
    println("HENSEL DEBUG: summarize got JSON: ${success.json}")
    val root = runCatching { json.decodeFromString<Root>(success.json) }.getOrNull() ?: return null
    if (root.manifests.isEmpty()) return null

    val active =
        root.activeManifest?.let { root.manifests[it] } ?: root.manifests.values.firstOrNull()

    val best = root.manifests.values
        .asSequence()
        .flatMap { it.assertions.asSequence() }
        .filterIsInstance<ActionsAssertion>()
        .flatMap { it.data.actions.asSequence() }
        .map { it.toCandidate() }
        .maxByOrNull { it.score }
        ?: EMPTY_CANDIDATE

    return ManifestSummary(
        aiStatus = best.status,
        tool = best.tool ?: active?.claimGenerator,
        action = best.action,
        title = active?.title,
        format = active?.format,
    )
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class Root(
    @SerialName("active_manifest") val activeManifest: String? = null,
    val manifests: Map<String, Manifest> = emptyMap(),
)

@Serializable
private data class Manifest(
    @SerialName("claim_generator") val claimGenerator: String? = null,
    val title: String? = null,
    val format: String? = null,
    val assertions: List<Assertion> = emptyList(),
)

@Serializable(with = AssertionSerializer::class)
private sealed interface Assertion {
    val label: String
}

@Serializable
private data class ActionsAssertion(
    override val label: String,
    val data: ActionsData = ActionsData(),
) : Assertion

@Serializable
private data class OtherAssertion(
    override val label: String,
) : Assertion

/**
 * Dispatches on the `label` field: anything starting with `c2pa.actions` is decoded as an
 * [ActionsAssertion]; everything else as [OtherAssertion]. Keeps the outer decode total — no
 * single misshapen assertion (e.g. a `c2pa.hash.data` payload with its own schema) can break the
 * whole manifest decode.
 */
private object AssertionSerializer : JsonContentPolymorphicSerializer<Assertion>(Assertion::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Assertion> {
        val label = element.jsonObject["label"]?.jsonPrimitive?.content.orEmpty()
        return if (label.startsWith("c2pa.actions")) ActionsAssertion.serializer() else OtherAssertion.serializer()
    }
}

@Serializable
private data class ActionsData(
    val actions: List<Action> = emptyList(),
)

@Serializable
private data class Action(
    val action: String? = null,
    @Serializable(with = SoftwareAgentSerializer::class)
    val softwareAgent: String? = null,
    val digitalSourceType: String? = null,
)

/**
 * `softwareAgent` is polymorphic in the C2PA actions schema:
 *   - v1 actions: plain String (e.g. "Adobe Firefly").
 *   - v2 actions: `{ name: String, version?: String, ... }` Object.
 * Normalized to the name String so downstream code stays simple.
 */
private object SoftwareAgentSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SoftwareAgent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> if (element.isString) element.content else null
            is JsonObject -> (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) encoder.encodeString(value) else encoder.encodeNull()
    }
}

// IPTC digitalSourceType URIs. Ref: https://cv.iptc.org/newscodes/digitalsourcetype/
private val aiGeneratedSuffixes = setOf(
    "trainedAlgorithmicMedia",
    "compositeWithTrainedAlgorithmicMedia",
    "trainedAlgorithmicRefinedMedia",
)
private val aiEditedSuffixes = setOf(
    "algorithmicMedia",
    "algorithmicallyEnhanced",
    "compositeCapture",
)

private fun Action.toCandidate(): Candidate {
    val suffix = digitalSourceType?.substringAfterLast('/')
    val status = when (suffix) {
        in aiGeneratedSuffixes -> AiStatus.AI_GENERATED
        in aiEditedSuffixes -> AiStatus.AI_EDITED
        null -> AiStatus.UNKNOWN
        else -> AiStatus.NOT_AI
    }
    return Candidate(status, action, softwareAgent)
}

private data class Candidate(val status: AiStatus, val action: String?, val tool: String?) {
    // Prefer a stronger AI status first; break ties in favor of candidates that carry a tool name.
    val score: Int = status.rank * 2 + if (tool != null) 1 else 0
}

private val EMPTY_CANDIDATE = Candidate(AiStatus.UNKNOWN, action = null, tool = null)

private val AiStatus.rank: Int
    get() = when (this) {
        AiStatus.AI_GENERATED -> 3
        AiStatus.AI_EDITED -> 2
        AiStatus.NOT_AI -> 1
        AiStatus.UNKNOWN -> 0
    }
