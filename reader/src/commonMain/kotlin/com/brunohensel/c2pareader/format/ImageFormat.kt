package com.brunohensel.c2pareader.format

/**
 * Internal classification of the image format detected from raw bytes.
 * Extended one variant per phase as new asset readers land.
 */
internal sealed class ImageFormat {
    data object Jpeg : ImageFormat()
    data object Png : ImageFormat()

    /**
     * Still-image HEIF / HEIC / AVIF — ISOBMFF (ISO/IEC 14496-12) container with a still-image
     * major brand. Video BMFF variants (mp4, mov, m4v, etc.) are out of scope and classify as
     * [Unknown].
     */
    data object Heif : ImageFormat()

    /** WebP — RIFF (Microsoft Multimedia) container with form type `"WEBP"`. */
    data object Webp : ImageFormat()

    /**
     * TIFF 6.0 or DNG — both share the identical wire format (byte-order marker, magic 42,
     * IFD tag walk). Detected via the `II*\0` or `MM\0*` header.
     */
    data object Tiff : ImageFormat()

    /**
     * A standalone `.c2pa` sidecar: the input bytes ARE a JUMBF manifest store with no image
     * container to strip. Detected by probing for the `jumb` box type at offset 4.
     */
    data object C2paSidecar : ImageFormat()
    data object Unknown : ImageFormat()
}
