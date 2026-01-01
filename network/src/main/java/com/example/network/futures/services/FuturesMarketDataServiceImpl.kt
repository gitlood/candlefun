package com.example.network.futures.services

import com.example.network.futures.config.FuturesEndpoints
import com.example.network.futures.dto.FuturesOpenInterestDto
import com.example.network.futures.dto.FuturesPremiumIndexDto
import com.example.network.futures.interfaces.FuturesMarketDataService
import com.example.network.helper.getOrThrow
import io.ktor.client.HttpClient

internal class FuturesMarketDataServiceImpl(
    private val client: HttpClient,
    private val endpoints: FuturesEndpoints
) : FuturesMarketDataService {
    init {
        val lower = endpoints.restBase.lowercase()
        if (lower.contains("demo.binance.com") || lower.contains("/en/") || lower.contains("/futures/")) {
            error("Invalid futures REST base (web UI URL): ${endpoints.restBase}")
        }
    }

    override suspend fun getPremiumIndex(symbol: String): FuturesPremiumIndexDto {
        return client.getOrThrow("${endpoints.restBase}/fapi/v1/premiumIndex") {
            url {
                parameters.append("symbol", symbol)
            }
        }
    }

    override suspend fun getOpenInterest(symbol: String): FuturesOpenInterestDto {
        return client.getOrThrow("${endpoints.restBase}/fapi/v1/openInterest") {
            url {
                parameters.append("symbol", symbol)
            }
        }
    }
}
