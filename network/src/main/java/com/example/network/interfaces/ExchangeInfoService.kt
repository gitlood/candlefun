package com.example.network.interfaces

import com.example.platform.model.MarketInfo

interface ExchangeInfoService {
    suspend fun getExchangeInfo(): MarketInfo
}
