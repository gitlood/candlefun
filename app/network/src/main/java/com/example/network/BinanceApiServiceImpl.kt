package com.example.network

import com.example.network.interfaces.BinanceApiService
import com.example.network.model.KlineResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal class BinanceApiServiceImpl(
    private val client: HttpClient = com.example.network.client.client
) : BinanceApiService {

    // Live Binance endpoint for spot data
    private val baseUrl = "https://api.binance.com/api/v3"

    override suspend fun getKlines(
        symbol: String,
        interval: String,
        limit: Int,
        startTime: Long?,
        endTime: Long?
    ): List<KlineResponse> {
        val response = client.get("$baseUrl/klines") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("interval", interval)
                parameters.append("limit", limit.toString())
                if (startTime != null) parameters.append("startTime", startTime.toString())
                if (endTime != null) parameters.append("endTime", endTime.toString())
            }
            // Spot historical klines do not require API key
        }

        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }

        val jsonArray = response.body<JsonArray>()
        return jsonArray.map { klineArray ->
            val kline = klineArray.jsonArray
            KlineResponse(
                openTime = kline[0].jsonPrimitive.long,
                open = kline[1].jsonPrimitive.content,
                high = kline[2].jsonPrimitive.content,
                low = kline[3].jsonPrimitive.content,
                close = kline[4].jsonPrimitive.content,
                volume = kline[5].jsonPrimitive.content,
                closeTime = kline[6].jsonPrimitive.long,
                quoteAssetVolume = kline[7].jsonPrimitive.content,
                numberOfTrades = kline[8].jsonPrimitive.int,
                takerBuyBaseAssetVolume = kline[9].jsonPrimitive.content,
                takerBuyQuoteAssetVolume = kline[10].jsonPrimitive.content,
                ignore = kline[11].jsonPrimitive.content
            )
        }
    }
}
