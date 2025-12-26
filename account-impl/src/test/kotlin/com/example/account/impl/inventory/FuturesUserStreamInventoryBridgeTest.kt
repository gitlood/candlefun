package com.example.account.impl.inventory

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.domain.inventory.InventoryPosition
import com.example.account.domain.inventory.InventoryStateRepository
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesUserStreamInventoryBridgeTest {

    @Test
    fun `start processes order trade updates and account updates`(): Unit = runBlocking {
        val inventory = RecordingInventory()
        val userDataService = RecordingUserDataService()
        val wsService = RecordingWebSocketService(
            messages = listOf(orderTradeUpdateJson("BUY", "0.5", "100.0"), accountUpdateJson())
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = FuturesUserStreamInventoryBridge(userDataService, wsService, inventory, scope)

        bridge.start()

        withTimeout(1_000L) {
            while (inventory.fills.size < 1 || inventory.positions.isEmpty()) {
                delay(10)
            }
        }

        assertEquals(1, userDataService.createCalls)
        assertEquals("listen-key", wsService.lastListenKey)
        assertEquals(1, inventory.fills.size)
        assertEquals("BTCUSDT", inventory.fills[0].symbol.value)
        assertEquals(0.5, inventory.fills[0].signedQty.toDouble(), 0.0)
        assertEquals(100.0, inventory.fills[0].price.value.toDouble(), 0.0)

        assertEquals(1, inventory.positions.size)
        assertEquals("BTCUSDT", inventory.positions[0].symbol.value)
        assertEquals(0.1, inventory.positions[0].quantity.toDouble(), 0.0)
        assertEquals(200.0, inventory.positions[0].avgPrice.value.toDouble(), 0.0)

        scope.coroutineContext.cancel()
    }

    @Test
    fun `order trade update ignores invalid qty and applies sell sign`() = runBlocking {
        val inventory = RecordingInventory()
        val userDataService = RecordingUserDataService()
        val wsService = RecordingWebSocketService(
            messages = listOf(
                orderTradeUpdateJson("BUY", "bad", "100.0"),
                orderTradeUpdateJson("SELL", "1.0", "10.0")
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = FuturesUserStreamInventoryBridge(userDataService, wsService, inventory, scope)

        bridge.start()

        withTimeout(1_000L) {
            while (inventory.fills.size < 1) {
                delay(10)
            }
        }

        assertEquals(1, inventory.fills.size)
        assertTrue(inventory.fills[0].signedQty.toDouble() < 0.0)
        assertEquals(-1.0, inventory.fills[0].signedQty.toDouble(), 0.0)
        assertEquals(10.0, inventory.fills[0].price.value.toDouble(), 0.0)

        scope.coroutineContext.cancel()
    }

    private class RecordingInventory : InventoryStateRepository {
        val fills = mutableListOf<InventoryFill>()
        var positions: List<InventoryPosition> = emptyList()

        override fun streamInventory() = emptyFlow<List<InventoryPosition>>()

        override suspend fun getInventory(): List<InventoryPosition> = positions

        override suspend fun applyFill(fill: InventoryFill) {
            fills.add(fill)
        }

        override suspend fun applyPositionSnapshot(positions: List<InventoryPosition>) {
            this.positions = positions
        }

        override suspend fun applyMarkPrice(symbol: Symbol, markPrice: Price, timestampMs: Long) {
        }

        override suspend fun persist() {
        }
    }

    private class RecordingUserDataService : FuturesUserDataService {
        var createCalls = 0
        override suspend fun createListenKey(): String {
            createCalls++
            return "listen-key"
        }

        override suspend fun keepAliveListenKey(listenKey: String) {
        }

        override suspend fun closeListenKey(listenKey: String) {
        }
    }

    private class RecordingWebSocketService(
        private val messages: List<String>
    ) : FuturesWebSocketService {
        var lastListenKey: String? = null

        override fun connect(streams: List<String>) =
            emptyFlow<com.example.network.dto.WsEnvelopeDto>()

        override fun connectUserStream(listenKey: String): Flow<String> = flow {
            lastListenKey = listenKey
            messages.forEach { emit(it) }
        }
    }

    private fun orderTradeUpdateJson(side: String, qty: String, price: String): String {
        return """
            {
              "e": "ORDER_TRADE_UPDATE",
              "E": 123,
              "o": {
                "s": "BTCUSDT",
                "S": "$side",
                "o": "LIMIT",
                "X": "FILLED",
                "p": "100.0",
                "q": "1.0",
                "z": "1.0",
                "l": "$qty",
                "L": "$price",
                "T": 456
              }
            }
        """.trimIndent()
    }

    private fun accountUpdateJson(): String {
        return """
            {
              "e": "ACCOUNT_UPDATE",
              "E": 999,
              "a": {
                "B": [],
                "P": [
                  {"s": "BTCUSDT", "pa": "0.1", "ep": "200", "cr": "1", "up": "2"},
                  {"s": "BAD", "pa": "bad", "ep": "1", "cr": "0", "up": "0"}
                ]
              }
            }
        """.trimIndent()
    }
}
