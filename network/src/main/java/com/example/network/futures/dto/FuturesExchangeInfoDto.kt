package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesExchangeInfoDto(
    @SerialName("symbols") val symbols: List<FuturesExchangeSymbolDto> = emptyList()
)

@Serializable
data class FuturesExchangeSymbolDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("status") val status: String,
    @SerialName("filters") val filters: List<FuturesExchangeFilterDto> = emptyList()
)

@Serializable
data class FuturesExchangeFilterDto(
    @SerialName("filterType") val filterType: String,
    @SerialName("tickSize") val tickSize: String? = null,
    @SerialName("stepSize") val stepSize: String? = null,
    @SerialName("minQty") val minQty: String? = null,
    @SerialName("minNotional") val minNotional: String? = null,
    @SerialName("notional") val notional: String? = null
)
