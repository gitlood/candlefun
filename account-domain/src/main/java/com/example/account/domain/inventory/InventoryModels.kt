package com.example.account.domain.inventory

import com.example.account.domain.Money
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol

data class CsvWalletRow(
    val asset: String,
    val free: Double,
    val locked: Double,
    val avgPrice: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double
)

data class InventoryPosition(
    val symbol: Symbol,
    val quantity: Qty,
    val avgPrice: Price,
    val realizedPnl: Money,
    val unrealizedPnl: Money,
    val free: Qty,
    val locked: Qty
)

data class InventoryFill(
    val symbol: Symbol,
    val signedQty: Qty,
    val price: Price,
    val timestampMs: Long
)
