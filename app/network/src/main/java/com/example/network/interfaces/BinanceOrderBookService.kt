package com.example.network.interfaces

import com.example.network.BinanceOrderBookServiceImpl
import com.example.network.model.OrderBookDepth

interface BinanceOrderBookService {
    suspend fun getDepth(
        symbol: String,
        limit: Int = 100
    ): OrderBookDepth

    companion object {
        fun create(): BinanceOrderBookService {
            return BinanceOrderBookServiceImpl()
        }
    }
}
