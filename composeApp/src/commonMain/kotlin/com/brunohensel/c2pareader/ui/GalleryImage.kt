package com.brunohensel.c2pareader.ui

sealed class GalleryImage {
    abstract val id: String
    abstract val label: String

    /** Image bundled in composeResources/files/, addressed by its resource path. */
    data class Bundled(
        override val id: String,
        override val label: String,
        val resourcePath: String,
    ) : GalleryImage()

    /** Image picked from the device; bytes held in memory (POC). */
    data class Picked(
        override val id: String,
        override val label: String,
        val bytes: ByteArray,
    ) : GalleryImage() {
        override fun equals(other: Any?): Boolean = other is Picked && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }
}
