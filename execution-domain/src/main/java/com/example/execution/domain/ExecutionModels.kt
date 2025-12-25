package com.example.execution.domain

import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType


data class ExecutionOrder(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String?,
    val price: Double,
    val originalQty: Double,
    val executedQty: Double,
    val status: String,
    val type: OrderType,
    val side: OrderSide,
    val transactTime: Long
)

data class OrderRequest(
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val quantity: Double,
    val price: Double? = null,
    val timeInForce: String? = null,
    val clientOrderId: String? = null
)

data class OrderCancelRequest(
    val symbol: String,
    val orderId: Long? = null,
    val clientOrderId: String? = null
)

data class Position(
    val symbol: String,
    val quantity: Double,
    val averagePrice: Double
)

data class BalanceSnapshot(
    val asset: String,
    val free: Double,
    val locked: Double
)

data class Fill(
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val isBuyerMaker: Boolean
)
