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
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry

class BinanceExecutionGateway(
    private val api: BinanceTestNetApiService,
    private val accountStateRepository: AccountStateRepository
) : ExecutionGateway {
    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        val response = api.createOrder(
            symbol = request.symbol.value,
            side = request.side,
            type = request.type,
            quantity = request.quantity.value.toPlainString(),
            price = request.price?.value?.toPlainString(),
            timeInForce = request.timeInForce?.name
        )

        val order = ExecutionOrder(
            symbol = Symbol.of(response.symbol),
            orderId = response.orderId,
            clientOrderId = response.clientOrderId,
            price = Price.fromDouble(response.price),
            originalQty = Qty.fromDouble(response.origQty),
            executedQty = Qty.fromDouble(response.executedQty),
            status = OrderStatus.fromString(response.status),
            type = OrderType.valueOf(response.type),
            side = OrderSide.valueOf(response.side),
            timeInForce = TimeInForce.fromString(response.timeInForce),
            transactTimeMs = response.transactTime
        )
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

class BinanceAccountStateRepository(
    private val api: BinanceTestNetApiService
) : AccountStateRepository {
    override suspend fun getBalances(): List<BalanceSnapshot> {
        val info = api.fetchAccountInfo()
        return info.balances.map { bal ->
            BalanceSnapshot(
                asset = Asset.of(bal.asset),
                free = Qty.fromDouble(bal.free),
                locked = Qty.fromDouble(bal.locked)
            )
        }
    }

    override suspend fun getFills(symbol: Symbol, sinceTimeMs: Long?): List<Fill> {
        val trades = api.getMyTrades(symbol = symbol.value, startTime = sinceTimeMs)
        return trades.map { t ->
            Fill(
                symbol = symbol,
                price = Price.fromDouble(t.price),
                quantity = Qty.fromDouble(t.quantity),
                fillTimeMs = t.timestamp,
                isBuyerMaker = t.isBuyerMaker
            )
        }
    }
}

private fun com.example.platform.model.OrderResponse.toExecutionOrder(): ExecutionOrder {
    return ExecutionOrder(
        symbol = Symbol.of(symbol),
        orderId = orderId,
        clientOrderId = clientOrderId,
        price = Price.fromDouble(price),
        originalQty = Qty.fromDouble(origQty),
        executedQty = Qty.fromDouble(executedQty),
        status = OrderStatus.fromString(status),
        type = OrderType.valueOf(type),
        side = OrderSide.valueOf(side),
        timeInForce = TimeInForce.fromString(timeInForce),
        transactTimeMs = transactTime
    )
}
