package com.example.network

import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.model.OrderBookDepth
import com.example.network.model.OrderBookLevel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal class BinanceOrderBookServiceImpl(
    private val client: HttpClient = com.example.network.client.client
) : BinanceOrderBookService {

    private val baseUrl = "https://api.binance.com/api/v3"

    override suspend fun getDepth(symbol: String, limit: Int): OrderBookDepth {
        val response = client.get("$baseUrl/depth") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
            }
        }

        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }

        val json = response.body<JsonObject>()
        val lastUpdateId = json["lastUpdateId"]?.jsonPrimitive?.long ?: 0L
        val bids = parseLevels(json["bids"]?.jsonArray)
        val asks = parseLevels(json["asks"]?.jsonArray)
        return OrderBookDepth(
            lastUpdateId = lastUpdateId,
            bids = bids,
            asks = asks
        )
    }

    private fun parseLevels(levels: JsonArray?): List<OrderBookLevel> {
        if (levels == null) return emptyList()
        return levels.mapNotNull { entry ->
            val row = entry.jsonArray
            val price = row.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val qty = row.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            OrderBookLevel(price = price, quantity = qty)
        }
    }
}
