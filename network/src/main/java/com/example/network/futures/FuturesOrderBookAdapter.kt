package com.example.network.futures

import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.platform.model.OrderBook

internal class FuturesOrderBookAdapter(
    private val service: FuturesOrderBookService
) : BinanceOrderBookService {
    override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
        return service.getDepth(symbol, limit)
    }
}
