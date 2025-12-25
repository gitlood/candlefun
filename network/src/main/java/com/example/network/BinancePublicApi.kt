package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.dto.AggTradeDto
import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.KlineDto
import com.example.network.dto.OrderBookDto
import com.example.network.dto.Ticker24HrDto
import com.example.network.helper.getOrThrow
import com.example.network.interfaces.BinancePublicApi
import com.example.platform.model.enums.KlineInterval
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter

internal class BinancePublicApiImpl(
    private val client: HttpClient,
    private val endpoints: BinanceEndpoints
) : BinancePublicApi {
    override suspend fun getKlines(
        symbol: String,
        interval: KlineInterval,
        limit: Int,
        startTime: Long?,
        endTime: Long?
    ): List<KlineDto> {
        return client.getOrThrow("${endpoints.restBase}/klines") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("interval", interval.value)
                parameters.append("limit", limit.toString())
                if (startTime != null) parameters.append("startTime", startTime.toString())
                if (endTime != null) parameters.append("endTime", endTime.toString())
            }
        }
    }

    override suspend fun getTickers24hr(): List<Ticker24HrDto> {
        return client.getOrThrow("${endpoints.restBase}/ticker/24hr") {
        }
    }

    override suspend fun getAggTrades(
        symbol: String,
        fromId: Long?,
        limit: Int
    ): List<AggTradeDto> {
        return client.getOrThrow("${endpoints.restBase}/aggTrades") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
                if (fromId != null) parameters.append("fromId", fromId.toString())
            }
        }
    }

    override suspend fun getExchangeInfo(): ExchangeInfoDto {
        return client.getOrThrow("${endpoints.restBase}/exchangeInfo")
    }

    override suspend fun getDepth(
        symbol: String,
        limit: Int
    ): OrderBookDto {
        return client.getOrThrow("${endpoints.restBase}/depth") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
            }
        }
    }
}
