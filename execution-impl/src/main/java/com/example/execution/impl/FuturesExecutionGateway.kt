package com.example.execution.impl

import com.example.account.domain.AccountStateRepository
import com.example.account.domain.Asset
import com.example.account.domain.BalanceSnapshot
import com.example.account.domain.Fill
import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.TimeInForce
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry

class FuturesExecutionGateway(
    private val api: BinanceFuturesTestNetApiService,
    private val accountStateRepository: AccountStateRepository
) : ExecutionGateway {
    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        val order = try {
            val response = api.createOrder(
                symbol = request.symbol.value,
                side = request.side,
                type = request.type,
                quantity = request.quantity.value.toPlainString(),
                price = request.price?.value?.toPlainString(),
                timeInForce = request.timeInForce?.name,
                reduceOnly = null,
                clientOrderId = request.clientOrderId
            )
            response.toExecutionOrder()
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val timeout = msg.contains("-1007") || msg.contains("Timeout waiting for response")
            if (!timeout || request.clientOrderId.isNullOrBlank()) throw e
            val open = api.getOpenOrders(request.symbol.value)
            val matched = open.firstOrNull { it.clientOrderId == request.clientOrderId }
            matched?.toExecutionOrder() ?: throw e
        }
        emitOrderEvent(order, "NEW")
        return order
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        val response = api.cancelOrder(
            symbol = request.symbol.value,
            orderId = request.orderId,
            clientOrderId = request.clientOrderId
        )
        return response.toExecutionOrder().also { order ->
            emitOrderEvent(order, "CANCEL")
        }
    }

    override suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder {
        Telemetry.emit(
            type = "order_event",
            tsMs = System.currentTimeMillis(),
            data = mapOf(
                "event_type" to "REPLACE",
                "symbol" to newRequest.symbol.value,
                "client_order_id" to newRequest.clientOrderId
            )
        )
        cancelOrder(cancelRequest)
        return placeOrder(newRequest)
    }

    override suspend fun getOpenOrders(symbol: Symbol?): List<ExecutionOrder> {
        return api.getOpenOrders(symbol?.value).map { it.toExecutionOrder() }
    }

    override suspend fun getPositions(): List<Position> {
        return accountStateRepository.getBalances().map { bal ->
            Position(
                symbol = Symbol.of(bal.asset.value),
                quantity = bal.free + bal.locked,
                averagePrice = Price.ZERO
            )
        }
    }

    private fun emitOrderEvent(order: ExecutionOrder, eventType: String) {
        Telemetry.emit(
            type = "order_event",
            tsMs = order.transactTimeMs,
            data = mapOf(
                "event_type" to eventType,
                "symbol" to order.symbol.value,
                "order_id" to order.orderId,
                "client_order_id" to order.clientOrderId,
                "side" to order.side.name,
                "type" to order.type.name,
                "price" to order.price.value.toDouble(),
                "qty" to order.originalQty.value.toDouble(),
                "executed_qty" to order.executedQty.value.toDouble(),
                "status" to order.status.name,
                "time_in_force" to order.timeInForce?.name
            )
        )
    }
}

class FuturesAccountStateRepository(
    private val api: BinanceFuturesTestNetApiService
) : AccountStateRepository {
    override suspend fun getBalances(): List<BalanceSnapshot> {
        val info = api.getAccountInfo()
        return info.assets.map { asset ->
            BalanceSnapshot(
                asset = Asset.of(asset.asset),
                free = Qty.fromString(asset.availableBalance),
                locked = Qty.fromString(asset.walletBalance) - Qty.fromString(asset.availableBalance)
            )
        }
    }

    override suspend fun getFills(symbol: Symbol, sinceTimeMs: Long?): List<Fill> {
        val trades = api.getUserTrades(symbol.value, startTime = sinceTimeMs)
        return trades.map { t ->
            Fill(
                symbol = Symbol.of(t.symbol),
                price = Price.fromString(t.price),
                quantity = Qty.fromString(t.quantity),
                fillTimeMs = t.time,
                isBuyerMaker = t.maker
            )
        }
    }
}

private fun com.example.network.futures.dto.FuturesOrderDto.toExecutionOrder(): ExecutionOrder {
    return ExecutionOrder(
        symbol = Symbol.of(symbol),
        orderId = orderId,
        clientOrderId = clientOrderId,
        price = Price.fromString(price),
        originalQty = Qty.fromString(origQty),
        executedQty = Qty.fromString(executedQty),
        status = OrderStatus.fromString(status),
        type = OrderType.valueOf(type),
        side = OrderSide.valueOf(side),
        timeInForce = TimeInForce.fromString(timeInForce),
        transactTimeMs = updateTime ?: 0L
    )
}
