package com.example.execution.domain

import com.example.account.domain.Position
import com.example.account.domain.Symbol
import com.example.account.domain.BalanceSnapshot

interface ExecutionGateway {
    suspend fun placeOrder(request: OrderRequest): ExecutionOrder
    suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder
    suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder
    suspend fun getOpenOrders(symbol: Symbol? = null): List<ExecutionOrder>
    suspend fun getPositions(): List<Position>
    suspend fun getBalances(): List<BalanceSnapshot>
}
