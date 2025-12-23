package com.example.network

import com.example.network.client.client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BinanceMarketDataService(
    private val httpClient: HttpClient = client
) {
    private val baseUrl = "https://api.binance.com/api/v3"

    suspend fun get24HrTickers(): List<Ticker24Hr> {
        val response = httpClient.get("$baseUrl/ticker/24hr")
        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }
        val json = response.body<JsonArray>()
        return json.mapNotNull { entry ->
            val obj = entry.jsonObject
            val symbol = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val quoteVolume = obj["quoteVolume"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val tradeCount = obj["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val lastPrice = obj["lastPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            Ticker24Hr(
                symbol = symbol,
                quoteVolume = quoteVolume,
                tradeCount = tradeCount,
                lastPrice = lastPrice
            )
        }
    }

    suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<AggTrade> {
        val response = httpClient.get("$baseUrl/aggTrades") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
                if (fromId != null) parameters.append("fromId", fromId.toString())
            }
        }
        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }
        val json = response.body<JsonArray>()
        return json.mapNotNull { entry ->
            val obj = entry.jsonObject
            val tradeId = obj["a"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val price = obj["p"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val qty = obj["q"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val timestamp = obj["T"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val isBuyerMaker = obj["m"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            AggTrade(
                tradeId = tradeId,
                price = price,
                quantity = qty,
                timestamp = timestamp,
                isBuyerMaker = isBuyerMaker
            )
        }
    }
}

data class AggTrade(
    val tradeId: Long,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val isBuyerMaker: Boolean
)

data class Ticker24Hr(
    val symbol: String,
    val quoteVolume: Double,
    val tradeCount: Int,
    val lastPrice: Double
)
