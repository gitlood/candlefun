package com.example.avellaneda

data class AvellanedaMmConfig(
    val symbol: String,
    val orderQty: Double,
    val minSpreadPct: Double,
    val minNotional: Double?,
    val inventorySkew: Double,
    val maxInventory: Double,
    val priceTick: Double,
    val qtyStep: Double,
    val quoteRefreshMs: Long,
    val maxQuoteAgeMs: Long,
    val maxSpreadPct: Double?,
    val minTopDepth: Double?,
    val maxDepthImbalance: Double?,
    val maxTradeImbalance1s: Double?,
    val minTradeCount1sForToxicity: Int,
    val maxVol1s: Double?,
    val maxVol5s: Double?,
    val maxVol10s: Double?,
    val logGateDecisions: Boolean
) {
    companion object {
        fun default(symbol: String): AvellanedaMmConfig {
            return AvellanedaMmConfig(
                symbol = symbol,
                orderQty = 0.001,
                minSpreadPct = 0.0005,
                minNotional = null,
                inventorySkew = 0.01,
                maxInventory = 0.01,
                priceTick = 0.01,
                qtyStep = 0.0001,
                quoteRefreshMs = 500,
                maxQuoteAgeMs = 5_000,
                maxSpreadPct = null,
                minTopDepth = null,
                maxDepthImbalance = null,
                maxTradeImbalance1s = null,
                minTradeCount1sForToxicity = 5,
                maxVol1s = null,
                maxVol5s = null,
                maxVol10s = null,
                logGateDecisions = false
            )
        }
    }
}
