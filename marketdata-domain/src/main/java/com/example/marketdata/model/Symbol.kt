package com.example.marketdata.model

@JvmInline
value class Symbol(val value: String) {
    init {
        require(value.isNotBlank()) { "symbol must not be blank" }
        require(value == value.trim().uppercase()) { "symbol must be trimmed and uppercase" }
    }
}

fun String.asSymbol(): Symbol = Symbol(trim().uppercase())
