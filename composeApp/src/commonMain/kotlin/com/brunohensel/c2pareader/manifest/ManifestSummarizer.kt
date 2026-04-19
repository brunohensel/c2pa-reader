package com.brunohensel.c2pareader.manifest

import com.brunohensel.c2pareader.C2paResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts a [ManifestSummary] from the reader's JSON output for display in the gallery UI.
 *
 * The reader's output has already had crypto/validation/ingredient fields stripped
 * (see `filterUnsupportedFields` in the reader tests), so this function only reads:
 * top-level `claim_generator`, `title`, `format`, and `assertions[*].data.actions[*]`
 * entries whose `label` starts with `c2pa.actions`.
 *
 * Walks every entry of `manifests` (not just `active_manifest`) so that AI/tool signals
 * living in child manifests still surface when the active one only has `c2pa.opened`.
 */
fun summarize(result: C2paResult): ManifestSummary? {
    val success = result as? C2paResult.Success ?: return null
    val root = runCatching { Json.parseToJsonElement(success.json).jsonObject }.getOrNull()
        ?: return null
    val manifests = (root["manifests"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: return null

    val activeUrn = (root["active_manifest"] as? JsonPrimitive)?.contentOrNullSafe()
    val active = activeUrn?.let { manifests[it] as? JsonObject } ?: manifests.values.firstOrNull() as? JsonObject

    // IPTC digitalSourceType URIs. Ref: https://cv.iptc.org/newscodes/digitalsourcetype/
    val aiGeneratedTypes = setOf(
        "trainedAlgorithmicMedia",
        "compositeWithTrainedAlgorithmicMedia",
        "trainedAlgorithmicRefinedMedia",
    )
    val aiEditedTypes = setOf(
        "algorithmicMedia",
        "algorithmicallyEnhanced",
        "compositeCapture",
    )

    var best: Candidate? = null
    for ((_, manifest) in manifests) {
        val mObj = manifest as? JsonObject ?: continue
        val assertions = (mObj["assertions"] as? JsonArray) ?: continue
        for (assertion in assertions) {
            val aObj = assertion as? JsonObject ?: continue
            val label = (aObj["label"] as? JsonPrimitive)?.contentOrNullSafe() ?: continue
            if (!label.startsWith("c2pa.actions")) continue
            val actions = (aObj["data"] as? JsonObject)?.get("actions") as? JsonArray ?: continue
            for (action in actions) {
                val actObj = action as? JsonObject ?: continue
                val actionName = (actObj["action"] as? JsonPrimitive)?.contentOrNullSafe()
                val dst = (actObj["digitalSourceType"] as? JsonPrimitive)?.contentOrNullSafe()
                val suffix = dst?.substringAfterLast('/')
                val status = when (suffix) {
                    in aiGeneratedTypes -> AiStatus.AI_GENERATED
                    in aiEditedTypes -> AiStatus.AI_EDITED
                    null -> AiStatus.UNKNOWN
                    else -> AiStatus.NOT_AI
                }
                val tool = actObj["softwareAgent"]?.toSoftwareAgentName()
                val candidate = Candidate(status, actionName, tool)
                val current = best
                if (current == null || candidate.beats(current)) best = candidate
            }
        }
    }

    val fallbackTool = (active?.get("claim_generator") as? JsonPrimitive)?.contentOrNullSafe()
    val title = (active?.get("title") as? JsonPrimitive)?.contentOrNullSafe()
    val format = (active?.get("format") as? JsonPrimitive)?.contentOrNullSafe()

    return ManifestSummary(
        aiStatus = best?.status ?: AiStatus.UNKNOWN,
        tool = best?.tool ?: fallbackTool,
        action = best?.action,
        title = title,
        format = format,
    )
}

private data class Candidate(val status: AiStatus, val action: String?, val tool: String?) {
    fun beats(other: Candidate): Boolean {
        // Prefer stronger AI status first, then prefer a candidate that carries a tool name.
        if (status.rank != other.status.rank) return status.rank > other.status.rank
        if (tool != null && other.tool == null) return true
        return false
    }
}

private val AiStatus.rank: Int
    get() = when (this) {
        AiStatus.AI_GENERATED -> 3
        AiStatus.AI_EDITED -> 2
        AiStatus.NOT_AI -> 1
        AiStatus.UNKNOWN -> 0
    }

private fun kotlinx.serialization.json.JsonElement.toSoftwareAgentName(): String? = when (this) {
    is JsonPrimitive -> contentOrNullSafe()
    is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNullSafe()
    else -> null
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else content.takeIf { it.isNotEmpty() && it != "null" }
