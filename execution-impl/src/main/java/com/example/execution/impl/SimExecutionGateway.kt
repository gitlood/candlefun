package com.example.execution.impl

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.BalanceSnapshot
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.Fill
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.Position

class SimExecutionGateway(
    private val accountStateRepository: AccountStateRepository
) : ExecutionGateway {
    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        throw NotImplementedError("SimExecutionGateway.placeOrder not implemented")
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        throw NotImplementedError("SimExecutionGateway.cancelOrder not implemented")
    }

    override suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder {
        throw NotImplementedError("SimExecutionGateway.replaceOrder not implemented")
    }

    override suspend fun getOpenOrders(symbol: String?): List<ExecutionOrder> {
        throw NotImplementedError("SimExecutionGateway.getOpenOrders not implemented")
    }

    override suspend fun getPositions(): List<Position> {
        return accountStateRepository.getBalances().map { bal ->
            Position(symbol = bal.asset, quantity = bal.free + bal.locked, averagePrice = 0.0)
        }
    }
}

class SimAccountStateRepository : AccountStateRepository {
    override suspend fun getBalances(): List<BalanceSnapshot> {
        throw NotImplementedError("SimAccountStateRepository.getBalances not implemented")
    }

    override suspend fun getFills(symbol: String, sinceMs: Long?): List<Fill> {
        throw NotImplementedError("SimAccountStateRepository.getFills not implemented")
    }
}
