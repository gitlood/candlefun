package com.example.network

import com.example.network.dto.OrderBookDto
import com.example.network.helper.NetworkConstants.BASE_URL
import com.example.network.helper.getOrThrow
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.model.OrderBook
import com.example.network.model.OrderBookEntry
import io.ktor.client.HttpClient

internal class BinanceOrderBookServiceImpl(
    private val client: HttpClient
) : BinanceOrderBookService {

    override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
        val dto = client.getOrThrow<OrderBookDto>("$BASE_URL/depth") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
            }
        }

        return OrderBook(
            lastUpdateId = dto.lastUpdateId,
            bids = dto.bids.map { entry -> 
                OrderBookEntry(
                    price = entry.price.toDoubleOrNull() ?: 0.0,
                    quantity = entry.quantity.toDoubleOrNull() ?: 0.0
                )
            },
            asks = dto.asks.map { entry -> 
                OrderBookEntry(
                    price = entry.price.toDoubleOrNull() ?: 0.0,
                    quantity = entry.quantity.toDoubleOrNull() ?: 0.0
                )
            }
        )
    }
}
