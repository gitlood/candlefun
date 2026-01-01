package com.example.network.futures.helper

import com.example.network.futures.dto.FuturesOrderTradeUpdate
import com.example.network.futures.dto.FuturesUserDataEvent
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.platform.report.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

object FuturesUserStreamTelemetry {
    private val json = Json { ignoreUnknownKeys = true }

    fun start(
        scope: CoroutineScope,
        userDataService: FuturesUserDataService,
        webSocketService: FuturesWebSocketService
    ): Job {
        return scope.launch {
        val listenKey = userDataService.createListenKey()
            launch {
                webSocketService.connectUserStream(listenKey).collect { raw ->
                    runCatching {
                        val event = json.decodeFromString(FuturesUserDataEvent.serializer(), raw)
                        if (event is FuturesOrderTradeUpdate) {
                            emitOrderUpdate(event)
                        }
                    }
                }
            }
            launch {
                while (true) {
                    delay(30 * 60 * 1000L)
                    runCatching { userDataService.keepAliveListenKey(listenKey) }
                }
            }
        }
    }

    private fun emitOrderUpdate(event: FuturesOrderTradeUpdate) {
        val order = event.order
        Telemetry.emit(
            type = "order_event",
            tsMs = event.eventTime,
            data = mapOf(
                "event_type" to order.orderStatus,
                "symbol" to order.symbol,
                "side" to order.side,
                "type" to order.orderType,
                "price" to order.price,
                "qty" to order.origQty,
                "executed_qty" to order.cumQty
            )
        )
        val lastQty = order.lastFilledQty.toDoubleOrNull() ?: 0.0
        val lastPrice = order.lastFilledPrice.toDoubleOrNull() ?: 0.0
        if (lastQty > 0.0 && lastPrice > 0.0) {
            Telemetry.emit(
                type = "fill_event",
                tsMs = if (order.tradeTime > 0L) order.tradeTime else event.eventTime,
                data = mapOf(
                    "symbol" to order.symbol,
                    "side" to order.side,
                    "price" to lastPrice,
                    "qty" to lastQty
                )
            )
        }
    }
}
