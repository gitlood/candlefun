package com.example.network

import com.example.network.model.KlineResponse
import com.example.network.model.OrderBookDepth
import com.example.network.model.OrderBookLevel
import com.example.network.model.TradeResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkModelsTest {
    @Test
    fun networkModels_holdValues() {
        val kline = KlineResponse(
            openTime = 1L,
            open = "1",
            high = "2",
            low = "0.5",
            close = "1.5",
            volume = "10",
            closeTime = 2L,
            quoteAssetVolume = "20",
            numberOfTrades = 3,
            takerBuyBaseAssetVolume = "5",
            takerBuyQuoteAssetVolume = "7",
            ignore = "0"
        )
        assertEquals("1.5", kline.close)

        val depth = OrderBookDepth(
            lastUpdateId = 9L,
            bids = listOf(OrderBookLevel(price = 1.0, quantity = 2.0)),
            asks = listOf(OrderBookLevel(price = 1.1, quantity = 3.0))
        )
        assertEquals(9L, depth.lastUpdateId)

        val trade = TradeResponse(
            symbol = "ETHUSDT",
            orderId = 1L,
            clientOrderId = "cid",
            transactTime = 1L
        )
        assertEquals("ETHUSDT", trade.symbol)
    }
}
