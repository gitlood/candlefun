package com.example.execution.impl

import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class SimExecutionGatewayTest {

    @Test
    fun `fills update positions`() {
        kotlinx.coroutines.runBlocking {
            val repo = SimAccountStateRepository()
            val gateway = SimExecutionGateway(repo)

            gateway.placeOrder(
                OrderRequest(
                    symbol = Symbol.of("BTCUSDT"),
                    side = OrderSide.BUY,
                    type = OrderType.LIMIT,
                    quantity = Qty.fromDouble(1.0),
                    price = Price.fromDouble(100.0),
                    timeInForce = TimeInForce.GTC
                )
            )

            val now = System.currentTimeMillis() + 1_000L
            val state = MarketState(
                symbol = "BTCUSDT",
                timestampMs = now,
                eventTimeMs = now,
                bestBidPrice = 99.0,
                bestBidQty = 1.0,
                bestAskPrice = 101.0,
                bestAskQty = 1.0,
                midPrice = 100.0,
                spread = 2.0,
                microPrice = 100.0,
                depthImbalance = 0.0,
                ofi1s = 0.0,
                tradeCount1s = 1,
                tradeVolume1s = 1.0,
                tradeImbalance1s = 0.0,
                lastTradePrice = 100.0,
                lastTradeQty = 1.0,
                lastTradeIsBuyerMaker = true,
                vol1s = null,
                vol5s = null,
                vol10s = null,
                vol1m = null,
                vol5m = null,
                bookUpdateId = 1L,
                bidLevels = emptyList(),
                askLevels = emptyList()
            )

            gateway.onMarketState(state)
            val positions = gateway.getPositions()

            assertEquals(1, positions.size)
            assertEquals("BTCUSDT", positions[0].symbol.value)
            assertEquals(1.0, positions[0].quantity.toDouble(), 0.0)
        }
    }
}
