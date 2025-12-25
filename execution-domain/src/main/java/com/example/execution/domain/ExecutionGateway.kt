package com.example.execution.domain

interface ExecutionGateway {
    suspend fun placeOrder(request: OrderRequest): ExecutionOrder
    suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder
    suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder
    suspend fun getOpenOrders(symbol: String? = null): List<ExecutionOrder>
    suspend fun getPositions(): List<Position>
}
