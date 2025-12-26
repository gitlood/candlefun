package com.example.execution.domain

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

data class ExecutionOrder(
    val symbol: Symbol,
    val orderId: Long,
    val clientOrderId: String?,
    val price: Price,
    val originalQty: Qty,
    val executedQty: Qty,
    val status: OrderStatus,
    val type: OrderType,
    val side: OrderSide,
    val timeInForce: TimeInForce?,
    val transactTimeMs: Long
)

data class OrderRequest(
    val symbol: Symbol,
    val side: OrderSide,
    val type: OrderType,
    val quantity: Qty,
    val price: Price? = null,
    val timeInForce: TimeInForce? = null,
    val clientOrderId: String? = null
)

data class OrderCancelRequest(
    val symbol: Symbol,
    val orderId: Long? = null,
    val clientOrderId: String? = null
)
