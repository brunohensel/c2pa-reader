package com.brunohensel.c2pareader.manifest

/**
 * UI-facing summary of a C2PA manifest for display on an image card.
 *
 * Only reflects fields the app has at its disposal after the reader's
 * `filterUnsupportedFields` strips crypto/validation/ingredient data. No date —
 * the only timestamp (`signature_info.time`) is stripped.
 */
data class ManifestSummary(
    val aiStatus: AiStatus,
    val tool: String?,
    val action: String?,
    val title: String?,
    val format: String?,
)

enum class AiStatus { AI_GENERATED, AI_EDITED, NOT_AI, UNKNOWN }
