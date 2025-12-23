package com.example.platformutil

import java.time.ZoneId

// ========================= PROFIT GROUP CONFIG =========================
data class ProfitGroupConfig(
    /** FRACTIONS: ETH micro-pattern thresholds (auto-scaled from TP if needed) */
    val thresholds: DoubleArray = doubleArrayOf(0.008, 0.006, 0.004),  // 0.8%, 0.6%, 0.4%
    val localLowLookbackMinutes: Int = 5,                              // lookback for local lows
    val mode: ProfitGroupMode = ProfitGroupMode.TP_HIT,

    /** Maximum allowed drawdown per group (FRACTION, 0.05 = 5%) */
    val maxDrawdownAllowed: Double = 0.05,                             // 5%

    val requireContinuous: Boolean = true,                             // require strict continuity
    val dedupeOverlapping: Boolean = true,                             // remove overlapping groups
    val sortBy: ProfitGroupSort = ProfitGroupSort.GAIN_DESC,           // sort by gain

    /** Console/reporting */
    val maxGroupsToPrint: Int = Int.MAX_VALUE,
    val printReport: Boolean = true,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProfitGroupConfig

        if (localLowLookbackMinutes != other.localLowLookbackMinutes) return false
        if (maxDrawdownAllowed != other.maxDrawdownAllowed) return false
        if (requireContinuous != other.requireContinuous) return false
        if (dedupeOverlapping != other.dedupeOverlapping) return false
        if (maxGroupsToPrint != other.maxGroupsToPrint) return false
        if (printReport != other.printReport) return false
        if (!thresholds.contentEquals(other.thresholds)) return false
        if (mode != other.mode) return false
        if (sortBy != other.sortBy) return false
        if (zoneId != other.zoneId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = localLowLookbackMinutes
        result = 31 * result + maxDrawdownAllowed.hashCode()
        result = 31 * result + requireContinuous.hashCode()
        result = 31 * result + dedupeOverlapping.hashCode()
        result = 31 * result + maxGroupsToPrint
        result = 31 * result + printReport.hashCode()
        result = 31 * result + thresholds.contentHashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + sortBy.hashCode()
        result = 31 * result + zoneId.hashCode()
        return result
    }
}

// ========================= BACKTEST CONFIG =========================
data class BacktestConfig(
    val lookbackMinutes: Int = 60,                 // 1h lookback for RuleGate features

    /** FRACTIONS */
    val takeProfit: Double = 0.008,                // 0.8%
    val stopLoss: Double = 0.004,                  // 0.4%

    /** Costs (FRACTIONS) */
    val feePerSide: Double = BINANCE_FEE,
    val slippagePerSide: Double = 0.0001,

    val worstCaseIfBothHit: Boolean = true,
    val allowOverlappingTrades: Boolean = false,

    /** Horizon for expected trade completion */
    val horizonMinutes: Int = 60,                  // ~1h

    /** Candle size */
    val intervalMillis: Long = 5 * 60_000L,        // 5-minute candles
)

// ========================= EVENT STUDY CONFIG =========================
data class EventStudyConfig(
    val negativeSampleEveryN: Int = 50,
    val seed: Int = 1337,

    val patternBars: Int = 3,                      // short ETH patterns
    val contextBars: Int = 25,                     // context length for quality

    val topK: Int = 50,

    /**
     * IMPORTANT:
     * Raising this reduces “rare winners” and increases robustness.
     * For “trade regularly”, start at 5. (Was 2.)
     */
    val minPosCount: Int = 5,

    val maxNegatives: Int = 50_000,

    /** Minimum net edge (fraction) to count a positive event. */
    val minNetEdge: Double = 0.0,

    /** Extra embargo time after a positive window (minutes). */
    val embargoMinutes: Int = 0,

    /** Stability filtering across time folds (1 disables). */
    val stabilityFolds: Int = 1,
    val minStableFolds: Int = 1,
    val minPosPerFold: Int = 1,

    /** Multiple testing control (<=0 disables, 0.1 = 10% FDR). */
    val maxFdr: Double = 0.1,

    /** Regime diversity: trend/vol buckets. */
    val regimeMinBuckets: Int = 1,
    val regimeMinPosPerBucket: Int = 1,

    // ✅ quality gate
    val minPosEventsToRun: Int = 10,
    val minNegSamplesToRun: Int = 500,
    val minDistinctFullKeysToRun: Int = 30,

    /** Console/reporting */
    val printReport: Boolean = true,
)

// ========================= SIGNAL CONFIG =========================
data class SignalConfig(
    /**
     * Minimum allowed 30m return (FRACTION).
     * Example: -0.01 means “don’t long if down more than 1% in last 30m”.
     */
    val ret30mMin: Double = -0.01,

    /**
     * Minimum volume z-score.
     * If 0.0 you block ~half the market. For more trades use negatives.
     */
    val volumeZMin: Double = -0.5,

    /**
     * contraction = recentVolStd / baselineVolStd
     * You want this <= some MAX. (Name was previously misleading.)
     */
    val contractionMax: Double = 1.10,

    /**
     * Optional: require non-negative trend slope for long entries.
     * 0.0 = allow flat. Small positive = more selective.
     */
    val trendSlopeMin: Double = 0.0,
)

// ========================= ORDER BOOK CONFIG =========================
data class OrderBookSignalConfig(
    val enabled: Boolean = false,
    val minImbalance10: Double = 0.05,
    val maxSpreadBps: Double = 15.0,
)

// ========================= CONFLUENCE CONFIG =========================
data class ConfluenceConfig(
    val enabled: Boolean = false,
    /** 0 = use backtest.horizonMinutes */
    val lookbackMinutes: Int = 0,
    val minMatches: Int = 1,
    val breakoutBufferPct: Double = 0.0005,
    val breakoutRequireCloseAbove: Boolean = false,
)

// ========================= ALGO CONFIG =========================
data class AlgoConfig(
    val profitGroup: ProfitGroupConfig = ProfitGroupConfig(),
    val backtest: BacktestConfig = BacktestConfig(),
    val eventStudy: EventStudyConfig = EventStudyConfig(),
    val signal: SignalConfig = SignalConfig(),
    val orderBook: OrderBookSignalConfig = OrderBookSignalConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
) {
    fun id(): String =
        "TP=${pct(backtest.takeProfit)} SL=${pct(backtest.stopLoss)} HZ=${backtest.horizonMinutes}m " +
                "DD=${pct(profitGroup.maxDrawdownAllowed)} LL=${profitGroup.localLowLookbackMinutes}m " +
                "SG(ret30=${pct(signal.ret30mMin)} volZ>=${fmt(signal.volumeZMin)} contr<=${fmt(signal.contractionMax)} slope>=${fmt(signal.trendSlopeMin)}) " +
                "OB(en=${orderBook.enabled} imb10>=${fmt(orderBook.minImbalance10)} spr<=${fmt(orderBook.maxSpreadBps)})" +
                confluenceSuffix()

    private fun pct(x: Double): String = "%.2f%%".format(x * 100.0)
    private fun fmt(x: Double): String = "%.2f".format(x)

    private fun confluenceSuffix(): String {
        if (!confluence.enabled) return ""
        val lb = if (confluence.lookbackMinutes <= 0) backtest.horizonMinutes else confluence.lookbackMinutes
        return " CF(lb=${lb}m min=${confluence.minMatches} buf=${pct(confluence.breakoutBufferPct)} close=${confluence.breakoutRequireCloseAbove})"
    }
}

enum class ProfitGroupSort { TIME_ASC, GAIN_DESC, DRAWDOWN_ASC }

enum class ProfitGroupMode { TP_HIT, NET_POSITIVE }
