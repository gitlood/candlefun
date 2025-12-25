package com.example.execution.impl

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.BalanceSnapshot
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.Fill
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.Position
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

class BinanceExecutionGateway(
    private val api: BinanceTestNetApiService,
    private val accountStateRepository: AccountStateRepository
) : ExecutionGateway {
    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        val response = api.createOrder(
            symbol = request.symbol,
            side = request.side,
            type = request.type,
            quantity = request.quantity.toString(),
            price = request.price?.toString(),
            timeInForce = request.timeInForce
        )

        return ExecutionOrder(
            symbol = response.symbol,
            orderId = response.orderId,
            clientOrderId = response.clientOrderId,
            price = response.price,
            originalQty = response.origQty,
            executedQty = response.executedQty,
            status = response.status,
            type = OrderType.valueOf(response.type),
            side = OrderSide.valueOf(response.side),
            transactTime = response.transactTime
        )
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        val response = api.cancelOrder(
            symbol = request.symbol,
            orderId = request.orderId,
            clientOrderId = request.clientOrderId
        )
        return response.toExecutionOrder()
    }

    override suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder {
        cancelOrder(cancelRequest)
        return placeOrder(newRequest)
    }

    override suspend fun getOpenOrders(symbol: String?): List<ExecutionOrder> {
        return api.getOpenOrders(symbol).map { it.toExecutionOrder() }
    }

    override suspend fun getPositions(): List<Position> {
        return accountStateRepository.getBalances().map { bal ->
            Position(symbol = bal.asset, quantity = bal.free + bal.locked, averagePrice = 0.0)
        }
    }
}

class BinanceAccountStateRepository(
    private val api: BinanceTestNetApiService
) : AccountStateRepository {
    override suspend fun getBalances(): List<BalanceSnapshot> {
        val info = api.fetchAccountInfo()
        return info.balances.map { bal ->
            BalanceSnapshot(asset = bal.asset, free = bal.free, locked = bal.locked)
        }
    }

    override suspend fun getFills(symbol: String, sinceMs: Long?): List<Fill> {
        val trades = api.getMyTrades(symbol = symbol, startTime = sinceMs)
        return trades.map { t ->
            Fill(
                symbol = symbol,
                price = t.price,
                quantity = t.quantity,
                timestamp = t.timestamp,
                isBuyerMaker = t.isBuyerMaker
            )
        }
    }
}

private fun com.example.platform.model.OrderResponse.toExecutionOrder(): ExecutionOrder {
    return ExecutionOrder(
        symbol = symbol,
        orderId = orderId,
        clientOrderId = clientOrderId,
        price = price,
        originalQty = origQty,
        executedQty = executedQty,
        status = status,
        type = OrderType.valueOf(type),
        side = OrderSide.valueOf(side),
        transactTime = transactTime
    )
}
