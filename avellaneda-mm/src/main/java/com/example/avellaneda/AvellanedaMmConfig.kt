package com.example.avellaneda

data class AvellanedaMmConfig(
    val symbol: String,
    val orderQty: Double,
    val minSpreadPct: Double,
    val inventorySkew: Double,
    val maxInventory: Double,
    val priceTick: Double,
    val qtyStep: Double,
    val quoteRefreshMs: Long,
    val maxQuoteAgeMs: Long
) {
    companion object {
        fun default(symbol: String): AvellanedaMmConfig {
            return AvellanedaMmConfig(
                symbol = symbol,
                orderQty = 0.001,
                minSpreadPct = 0.0005,
                inventorySkew = 0.01,
                maxInventory = 0.01,
                priceTick = 0.01,
                qtyStep = 0.0001,
                quoteRefreshMs = 500,
                maxQuoteAgeMs = 5_000
            )
        }
    }
}
