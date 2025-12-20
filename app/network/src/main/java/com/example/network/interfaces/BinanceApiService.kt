package com.example.network.interfaces

import com.example.network.BinanceApiServiceImpl
import com.example.network.model.KlineResponse

interface BinanceApiService {
    suspend fun getKlines(
        symbol: String,
        interval: String = "5m",
        limit: Int = 1000,
       // apiKey: String,
        startTime: Long? = null,
        endTime: Long? = null
    ): List<KlineResponse>

    companion object {
        fun create(): BinanceApiService {
            return BinanceApiServiceImpl()
        }
    }
}