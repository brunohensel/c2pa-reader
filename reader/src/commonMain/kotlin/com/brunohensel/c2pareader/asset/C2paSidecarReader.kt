package com.brunohensel.c2pareader.asset

/**
 * Handles a standalone `.c2pa` sidecar: the input bytes ARE the JUMBF manifest store, with
 * no image container to strip. [extractJumbf] is therefore the identity function.
 *
 * ## Reference implementation
 *
 * Pure-Kotlin analogue of the standalone-asset handling in c2pa-rs:
 * [sdk/src/asset_handlers/c2pa_io.rs](https://github.com/contentauth/c2pa-rs/blob/main/sdk/src/asset_handlers/c2pa_io.rs).
 *
 * ## Error contract
 *
 * Unlike [JpegAssetReader] or [PngAssetReader], this reader never inspects the payload — any
 * structural issue is discovered by `JumbfParser` downstream and surfaces as
 * `C2paError.JumbfError`, not `C2paError.Malformed`. That's the intended behavior: "malformed"
 * is reserved for the container-above-JUMBF layer, and a sidecar has no such layer.
 */
internal object C2paSidecarReader : AssetReader {
    override fun extractJumbf(bytes: ByteArray): ByteArray = bytes
}
