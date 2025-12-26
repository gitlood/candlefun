package com.example.execution.impl.inventory

import com.example.execution.domain.inventory.InventoryFill
import com.example.execution.domain.inventory.InventoryPosition
import com.example.execution.domain.inventory.InventoryStateRepository
import com.example.network.futures.dto.FuturesAccountUpdate
import com.example.network.futures.dto.FuturesOrderTradeUpdate
import com.example.network.futures.dto.FuturesUserDataEvent
import com.example.network.futures.dto.FuturesUserDataSerializer
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class FuturesUserStreamInventoryBridge(
    private val userDataService: FuturesUserDataService,
    private val wsService: FuturesWebSocketService,
    private val inventory: InventoryStateRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun start() {
        scope.launch {
            val listenKey = userDataService.createListenKey()
            launch { keepAlive(listenKey) }
            wsService.connectUserStream(listenKey).collect { msg ->
                val event = json.decodeFromString(FuturesUserDataSerializer, msg)
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: FuturesUserDataEvent) {
        when (event) {
            is FuturesOrderTradeUpdate -> handleOrderTrade(event)
            is FuturesAccountUpdate -> handleAccount(event)
        }
    }

    private suspend fun handleOrderTrade(event: FuturesOrderTradeUpdate) {
        val o = event.order
        val qty = o.lastFilledQty.toDoubleOrNull() ?: return
        if (qty <= 0.0) return
        val price = o.lastFilledPrice.toDoubleOrNull() ?: return
        val signedQty = if (o.side.equals("BUY", ignoreCase = true)) qty else -qty
        inventory.applyFill(
            InventoryFill(
                asset = o.symbol,
                signedQty = signedQty,
                price = price,
                timestampMs = o.tradeTime
            )
        )
    }

    private suspend fun handleAccount(event: FuturesAccountUpdate) {
        val positions = event.account.positions.mapNotNull { pos ->
            val qty = pos.positionAmt.toDoubleOrNull() ?: return@mapNotNull null
            val avg = pos.entryPrice.toDoubleOrNull() ?: 0.0
            val realized = pos.accumulatedRealized.toDoubleOrNull() ?: 0.0
            val unrealized = pos.unrealizedPnl.toDoubleOrNull() ?: 0.0
            InventoryPosition(
                asset = pos.symbol,
                quantity = qty,
                avgPrice = avg,
                realizedPnl = realized,
                unrealizedPnl = unrealized,
                free = 0.0,
                locked = 0.0
            )
        }
        inventory.applyPositionSnapshot(positions)
    }

    private suspend fun keepAlive(listenKey: String) {
        while (true) {
            delay(30 * 60 * 1000L)
            userDataService.keepAliveListenKey(listenKey)
        }
    }
}
