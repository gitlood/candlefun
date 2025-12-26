package com.example.network.futures.interfaces

import com.example.platform.model.OrderBook

interface FuturesOrderBookService {
    suspend fun getDepth(symbol: String, limit: Int): OrderBook
}
