package com.example.orderbookscalper

// ScalperConfig.kt

data class ScalperConfig(
    val quoteAsset: String,
    val fixedSymbols: Set<String>,
    val excludedSymbols: Set<String>,
    val maxActiveSymbols: Int,
    val maxScanSymbols: Int,
    val minQuoteVolume: Double,
    val minTradeCount: Int,

    // NEW: avoid penny/meme microstructure hell
    val minLastPrice: Double,

    val depthLevels: Int,
    val depthLimit: Int,
    val selectionIntervalMs: Long,
    val pollIntervalMs: Long,
    val tradeWindowMs: Long,
    val entryTtlMs: Long,
    val entryOffsetPctOfSpread: Double,
    val maxHoldMs: Long,

    val tpMult: Double,
    val slMult: Double,
    val trailArmMult: Double,
    val trailMult: Double,

    val imbalanceThresh: Double,
    val microEdgeThresh: Double,

    // NEW: require enough trades/notional so flowImb isn’t ±1.0 on tiny samples
    val minFlowTrades: Int,
    val minFlowNotional: Double,
    val flowImbalanceThresh: Double,

    val minSpreadPct: Double,
    val maxSpreadPct: Double,
    val feePctPerSide: Double,
    val exitSlippagePct: Double,
    val exitPriceBasis: ExitPriceBasis,

    val startingEquity: Double,
    val perSymbolMaxNotionalPct: Double,
    val maxSymbolNotional: Double,
    val maxOpenPositions: Int,
    val cooldownMs: Long,
    val maxDrawdownPct: Double,
    val minDepthNotional: Double,
    val minVolProxyPct: Double,
    val volWindowMs: Long,
    val maxStaleMs: Long,
    val aggTradesLimit: Int,
    val maxConcurrentRequests: Int,
    val statsIntervalMs: Long,
    val runMs: Long,

    // NEW: signal must persist N polls before we place an order
    val minSignalConsecutive: Int,

    val cancelOnSignalFlip: Boolean,
    val verbose: Boolean,
    val csvEnabled: Boolean,
    val csvDir: String
)

fun defaultScalperConfig(): ScalperConfig {
    val feePctPerSide = 0.0001
    val minSpreadPct = feePctPerSide * 6.0 // was 4.0: give yourself more room vs fees/noise

    return ScalperConfig(
        quoteAsset = "USDT",
        fixedSymbols = emptySet(),
        excludedSymbols = emptySet(),

        // Trade less, but higher quality
        maxActiveSymbols = 6,
        maxScanSymbols = 0,
        minQuoteVolume = 20_000_000.0,
        minTradeCount = 20_000,

        // NEW: filter out sub-5c coins (tune as you like)
        minLastPrice = 0.05,

        depthLevels = 5,
        depthLimit = 20,
        selectionIntervalMs = 60_000L,
        pollIntervalMs = 650L,

        // Give flow time to accumulate real samples
        tradeWindowMs = 5_000L,

        entryTtlMs = 2_000L,
        entryOffsetPctOfSpread = 0.10,
        maxHoldMs = 20_000L,

        // FIX EXPECTANCY: reward >= risk (was 0.6 / 1.2)
        tpMult = 1.20,
        slMult = 0.90,
        trailArmMult = 0.80,
        trailMult = 0.60,

        // Slightly stricter signals
        imbalanceThresh = 0.30,
        microEdgeThresh = 0.18,

        // NEW: stop flowImb=±1 on tiny samples
        minFlowTrades = 25,
        minFlowNotional = 10_000.0,
        flowImbalanceThresh = 0.25,

        minSpreadPct = minSpreadPct,
        maxSpreadPct = 0.0018,

        feePctPerSide = feePctPerSide,
        exitSlippagePct = 0.0,

        // More realistic exit basis; if this can’t work on best bid/ask, it won’t work live
        exitPriceBasis = ExitPriceBasis.BEST_BID_ASK,

        startingEquity = 10_000.0,
        perSymbolMaxNotionalPct = 0.01,
        maxSymbolNotional = 150.0,
        maxOpenPositions = 3,
        cooldownMs = 30_000L,
        maxDrawdownPct = 0.05,

        minDepthNotional = 200_000.0,
        minVolProxyPct = 0.0005,
        volWindowMs = 60_000L,
        maxStaleMs = 5_000L,

        aggTradesLimit = 1000,
        maxConcurrentRequests = 5,
        statsIntervalMs = 30_000L,
        runMs = 0L,

        // NEW: require signal to persist
        minSignalConsecutive = 2,

        cancelOnSignalFlip = true,
        verbose = false,
        csvEnabled = true,
        csvDir = "app/orderbookscalper"
    )
}
