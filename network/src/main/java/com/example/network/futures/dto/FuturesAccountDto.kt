package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesAccountDto(
    @SerialName("assets") val assets: List<FuturesAssetDto> = emptyList(),
    @SerialName("positions") val positions: List<FuturesPositionDto> = emptyList()
)

@Serializable
data class FuturesAssetDto(
    @SerialName("asset") val asset: String,
    @SerialName("walletBalance") val walletBalance: String,
    @SerialName("availableBalance") val availableBalance: String
)

@Serializable
data class FuturesPositionDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("positionAmt") val positionAmt: String,
    @SerialName("entryPrice") val entryPrice: String,
    @SerialName("unRealizedProfit") val unrealizedProfit: String = "0"
)
