package com.example.network.model

import kotlinx.serialization.Serializable

@Serializable
data class AggTrade(
    val tradeId: Long,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val isBuyerMaker: Boolean
)
