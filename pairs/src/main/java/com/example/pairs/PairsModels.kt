package com.example.pairs

data class PairSnapshot(
    val timestampMs: Long,
    val priceA: Double,
    val priceB: Double,
    val volA: Double?,
    val volB: Double?
)

data class PairsSignal(
    val timestampMs: Long,
    val spread: Double,
    val beta: Double,
    val mean: Double,
    val std: Double,
    val zScore: Double,
    val corr: Double
)
