package com.example.network.candlecollector

import com.example.platform.model.Kline

interface CandleStorePort : AutoCloseable {
    suspend fun init()
    suspend fun getMinOpenTime(symbol: String, interval: String): Long?
    suspend fun getMaxOpenTime(symbol: String, interval: String): Long?
    suspend fun getRecentOpenTimes(symbol: String, interval: String, limit: Int): List<Long>
    suspend fun deleteOldCandles(symbol: String, interval: String, cutoffTime: Long)
    suspend fun insertCandles(symbol: String, interval: String, klines: List<Kline>)
}
