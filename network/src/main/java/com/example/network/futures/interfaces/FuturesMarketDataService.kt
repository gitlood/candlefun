package com.example.network.futures.interfaces

import com.example.network.futures.dto.FuturesOpenInterestDto
import com.example.network.futures.dto.FuturesPremiumIndexDto

interface FuturesMarketDataService {
    suspend fun getPremiumIndex(symbol: String): FuturesPremiumIndexDto
    suspend fun getOpenInterest(symbol: String): FuturesOpenInterestDto
}
