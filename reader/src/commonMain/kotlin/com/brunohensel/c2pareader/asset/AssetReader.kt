package com.brunohensel.c2pareader.asset

/**
 * Per-format extractor that pulls the raw JUMBF manifest-store bytes out of a container
 * (JPEG, PNG, HEIF, etc.). The returned bytes, if non-null, are fed straight into `JumbfParser`.
 *
 * Returns `null` when the asset is well-formed but carries no C2PA manifest.
 * Throws [MalformedAssetException] when the container is structurally corrupt at a level
 * above the JUMBF layer (e.g. a JPEG APP11 segment whose declared length runs off the end
 * of the file). The orchestrator in `C2paReader` translates that into `C2paError.Malformed`.
 */
internal fun interface AssetReader {
    fun extractJumbf(bytes: ByteArray): ByteArray?
}

internal class MalformedAssetException(val reason: String) : RuntimeException(reason)
