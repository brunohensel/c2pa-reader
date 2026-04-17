package com.brunohensel.c2pareader

/**
 * Typed errors returned by [C2paReader.read].
 *
 * "No manifest on a supported format" is a normal return, not a throw — callers can
 * branch on the sealed hierarchy without try/catch.
 */
public sealed class C2paError {
    /** Supported format, but the asset carries no C2PA manifest store. */
    public data object NoManifest : C2paError()

    /** The input bytes don't match any format this library recognizes. */
    public data object UnsupportedFormat : C2paError()

    /** Format-level corruption detected above the JUMBF/CBOR layers (e.g. truncated JPEG APP11 segment). */
    public data class Malformed(val reason: String) : C2paError()

    /** JUMBF box-tree structural corruption. */
    public data class JumbfError(val reason: String) : C2paError()

    /** CBOR decoding failure inside an assertion payload or claim. */
    public data class CborError(val reason: String) : C2paError()
}
