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
    val gateCooldownMs: Long,
    val spreadWindowMs: Long,
    val minAvgSpreadPct: Double?,
    val maxAvgSpreadPct: Double?,
    val maxSpreadPct: Double?,
    val minTopDepth: Double?,
    val topDepthLevels: Int,
    val maxDepthImbalance: Double?,
    val maxTradeImbalance1s: Double?,
    val minTradeCount1sForToxicity: Int,
    val maxVol1s: Double?,
    val maxVol5s: Double?,
    val maxVol10s: Double?,
    val volSpreadMultiplier: Double,
    val adaptiveSpreadTargetBps: Double?,
    val adaptiveSpreadUpdateMs: Long,
    val quoteStyle: QuoteStyle,
    val logGateDecisions: Boolean,
    val makerFeePct: Double
) {
    companion object {
        fun default(symbol: String): AvellanedaMmConfig {
            return AvellanedaMmConfig(
                symbol = symbol,
                orderQty = 0.001,
                minSpreadPct = 0.0001,
                minNotional = null,
                inventorySkew = 0.001,
                maxInventory = 0.01,
                priceTick = 0.01,
                qtyStep = 0.0001,
                quoteRefreshMs = 500,
                maxQuoteAgeMs = 5_000,
                gateCooldownMs = 1_000,
                spreadWindowMs = 10_000,
                minAvgSpreadPct = null,
                maxAvgSpreadPct = null,
                maxSpreadPct = null,
                minTopDepth = null,
                topDepthLevels = 5,
                maxDepthImbalance = null,
                maxTradeImbalance1s = null,
                minTradeCount1sForToxicity = 5,
                maxVol1s = null,
                maxVol5s = null,
                maxVol10s = null,
                volSpreadMultiplier = 0.0,
                adaptiveSpreadTargetBps = null,
                adaptiveSpreadUpdateMs = 10_000L,
                quoteStyle = QuoteStyle.IMPROVE,
                logGateDecisions = false,
                makerFeePct = 0.0
            )
        }
    }
}
