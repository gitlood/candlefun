package com.example.platform.model

@JvmInline
value class Price(val value: Double)

@JvmInline
value class Qty(val value: Double)

@JvmInline
value class Symbol(val value: String)

data class Candle(
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
