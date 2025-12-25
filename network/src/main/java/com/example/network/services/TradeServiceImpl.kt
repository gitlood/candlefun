package com.example.network.services

import com.example.network.interfaces.BinancePublicApi
import com.example.network.interfaces.TradeService
import com.example.network.mapper.toDomain
import com.example.platform.model.Trade

internal class TradeServiceImpl(
    private val api: BinancePublicApi
) : TradeService {

    override suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<Trade> {
        return api.getAggTrades(symbol, fromId, limit).map { it.toDomain() }
    }
}
