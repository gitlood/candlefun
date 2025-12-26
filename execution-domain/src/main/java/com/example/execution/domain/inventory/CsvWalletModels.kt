package com.example.execution.domain.inventory


data class CsvWalletRow(
    val asset: String,
    val free: Double,
    val locked: Double,
    val avgPrice: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double
)

/**
 * For perps, `asset` should hold the symbol (e.g., BTCUSDT) to represent position inventory.
 */

data class InventoryPosition(
    val asset: String,
    val quantity: Double,
    val avgPrice: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val free: Double,
    val locked: Double
)

/**
 * Signed quantity: buy = positive, sell = negative.
 */

data class InventoryFill(
    val asset: String,
    val signedQty: Double,
    val price: Double,
    val timestampMs: Long
)
