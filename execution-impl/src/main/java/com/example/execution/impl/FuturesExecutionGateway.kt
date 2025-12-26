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

class FuturesExecutionGateway(
    private val api: BinanceFuturesTestNetApiService,
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
        return response.toExecutionOrder()
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        val response = api.cancelOrder(
            symbol = request.symbol.value,
            orderId = request.orderId,
            clientOrderId = request.clientOrderId
        )
        return response.toExecutionOrder()
    }

    override suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder {
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
