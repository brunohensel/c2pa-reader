package com.brunohensel.c2pareader

/**
 * Public entry point of the C2PA reader library.
 *
 * Pass a full image `ByteArray`; receive either a JSON string conforming to the
 * [C2PA reader-schema](https://opensource.contentauthenticity.org/docs/manifest/json-ref/reader-schema)
 * or a typed [C2paError]. The call is synchronous and CPU-bound; wrap in your own
 * dispatcher if you need to move it off the main thread.
 *
 * Scaffold only: the full JPEG read pipeline (format detection, APP11 JUMBF extraction,
 * JUMBF + CBOR decoding, reader-schema JSON assembly) ships in follow-up commits within
 * this branch. Until those land, every call returns `Failure(UnsupportedFormat)`.
 */
public object C2paReader {
    public fun read(bytes: ByteArray): C2paResult {
        return C2paResult.Failure(C2paError.UnsupportedFormat)
    }
}
