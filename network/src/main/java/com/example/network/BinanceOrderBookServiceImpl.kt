package com.example.network

import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinancePublicApi
import com.example.network.mapper.toDomain
import com.example.platform.model.OrderBook

internal class BinanceOrderBookServiceImpl(
    private val api: BinancePublicApi
) : BinanceOrderBookService {

    override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
        return api.getDepth(symbol, limit).toDomain()
    }
}
