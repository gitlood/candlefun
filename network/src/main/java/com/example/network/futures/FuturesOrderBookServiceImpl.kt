package com.example.network.futures

import com.example.network.futures.config.FuturesEndpoints
import com.example.network.dto.OrderBookDto
import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.network.helper.getOrThrow
import com.example.network.mapper.toDomain
import com.example.platform.model.OrderBook
import io.ktor.client.HttpClient

internal class FuturesOrderBookServiceImpl(
    private val client: HttpClient,
    private val endpoints: FuturesEndpoints
) : FuturesOrderBookService {
    override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
        val dto: OrderBookDto = client.getOrThrow("${endpoints.restBase}/depth") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
            }
        }
        return dto.toDomain()
    }
}
