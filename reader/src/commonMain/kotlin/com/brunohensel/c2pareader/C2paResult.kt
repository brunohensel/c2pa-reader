package com.brunohensel.c2pareader

/**
 * Result of [C2paReader.read]. Either a JSON string conforming to the C2PA reader-schema,
 * or a typed [C2paError].
 */
public sealed class C2paResult {
    public data class Success(val json: String) : C2paResult()
    public data class Failure(val error: C2paError) : C2paResult()
}
