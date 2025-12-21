package com.example.historicaldata.util

import com.example.historicaldata.interfaces.OrderBookRepository
import com.example.network.interfaces.BinanceOrderBookService
import com.example.historicaldata.util.OrderBookFeatureCalculator.buildSnapshot

class OrderBookCollector(
    private val orderBookService: BinanceOrderBookService,
    private val repository: OrderBookRepository
) {
    suspend fun collectOnce(symbol: String, limit: Int = 100) {
        val depth = orderBookService.getDepth(symbol = symbol, limit = limit)
        val snapshot = buildSnapshot(symbol, depth, System.currentTimeMillis())
        repository.insertSnapshot(snapshot)
    }
}
