package com.example.historicaldata.interfaces

import com.example.network.model.KlineResponse

interface CandleRepository {
    fun isDatabaseEmpty(): Boolean
    fun getLatestCandleOpenTime(): Long?
    fun getOldestCandleOpenTime(): Long?
    fun insertKlines(klines: List<KlineResponse>)
    fun cleanupOldCandles(): Int
}
