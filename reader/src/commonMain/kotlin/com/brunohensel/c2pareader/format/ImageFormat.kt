package com.brunohensel.c2pareader.format

/**
 * Internal classification of the image format detected from raw bytes.
 * Extended one variant per phase as new asset readers land.
 */
internal sealed class ImageFormat {
    data object Jpeg : ImageFormat()
    data object Unknown : ImageFormat()
}
