package com.brunohensel.c2pareader

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform