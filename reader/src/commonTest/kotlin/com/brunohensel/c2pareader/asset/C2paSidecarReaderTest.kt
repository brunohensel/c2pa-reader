package com.brunohensel.c2pareader.asset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

class C2paSidecarReaderTest {

    @Test
    fun extractJumbfReturnsInputContentUnchanged() {
        val input = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x6A, 0x75, 0x6D, 0x62, 0xDE.toByte(), 0xAD.toByte())
        assertContentEquals(input, C2paSidecarReader.extractJumbf(input))
    }

    @Test
    fun extractJumbfIsIdentity() {
        val input = ByteArray(64) { it.toByte() }
        // Identity — not a copy. Guards against accidental allocation churn on the hot path.
        assertSame(input, C2paSidecarReader.extractJumbf(input))
    }

    @Test
    fun extractJumbfOnEmptyInputReturnsEmpty() {
        val input = ByteArray(0)
        assertContentEquals(input, C2paSidecarReader.extractJumbf(input))
    }
}
