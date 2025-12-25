package com.example.network.interfaces

import com.example.network.dto.AggTradeDto
import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.KlineDto
import com.example.network.dto.OrderBookDto
import com.example.network.dto.Ticker24HrDto
import com.example.platform.model.enums.KlineInterval

interface BinancePublicApi {
    suspend fun getKlines(
        symbol: String,
        interval: KlineInterval,
        limit: Int,
        startTime: Long? = null,
        endTime: Long? = null
    ): List<KlineDto>

    suspend fun getTickers24hr(): List<Ticker24HrDto>

    suspend fun getAggTrades(
        symbol: String,
        fromId: Long? = null,
        limit: Int
    ): List<AggTradeDto>

    suspend fun getExchangeInfo(): ExchangeInfoDto

    suspend fun getDepth(
        symbol: String,
        limit: Int
    ): OrderBookDto
}
