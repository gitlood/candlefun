package com.example.network.interfaces

import com.example.network.BinanceTestNetApiServiceImpl
import com.example.network.model.TradeResponse

interface BinanceTestNetApiService {
    suspend fun createOrder(
        symbol: String,
        side: String,
        type: String,
        quantity: String,
        price: String? = null,
        timeInForce: String? = null
    ): TradeResponse

    companion object {
        fun create(): BinanceTestNetApiService {
            return BinanceTestNetApiServiceImpl()
        }
    }
}
