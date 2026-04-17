package com.brunohensel.c2pareader.format

/**
 * Internal classification of the image format detected from raw bytes.
 * Extended one variant per phase as new asset readers land.
 */
internal sealed class ImageFormat {
    data object Jpeg : ImageFormat()
    data object Png : ImageFormat()

    /**
     * A standalone `.c2pa` sidecar: the input bytes ARE a JUMBF manifest store with no image
     * container to strip. Detected by probing for the `jumb` box type at offset 4.
     */
    data object C2paSidecar : ImageFormat()
    data object Unknown : ImageFormat()
}
