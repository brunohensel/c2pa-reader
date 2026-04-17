package com.brunohensel.c2pareader

import kotlin.test.Test
import kotlin.test.assertEquals

class C2paReaderSmokeTest {
    @Test
    fun emptyInputReturnsUnsupportedFormat() {
        val result = C2paReader.read(byteArrayOf())
        assertEquals(C2paResult.Failure(C2paError.UnsupportedFormat), result)
    }
}
