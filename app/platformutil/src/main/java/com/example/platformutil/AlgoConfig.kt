package com.example.platformutil

import java.time.ZoneId

data class ProfitGroupConfig(
    /** FRACTIONS: 0.05 = 5% */
    val thresholds: DoubleArray = doubleArrayOf(0.05, 0.03, 0.02),
    val localLowLookbackMinutes: Int = 5,
    /** FRACTION: 0.20 = 20% */
    val maxDrawdownAllowed: Double = 0.20,

    val requireContinuous: Boolean = false,
    val dedupeOverlapping: Boolean = true,
    val sortBy: ProfitGroupSort = ProfitGroupSort.GAIN_DESC,

    /** Console/reporting */
    val maxGroupsToPrint: Int = Int.MAX_VALUE,
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
        if (!thresholds.contentEquals(other.thresholds)) return false
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
        result = 31 * result + thresholds.contentHashCode()
        result = 31 * result + sortBy.hashCode()
        result = 31 * result + zoneId.hashCode()
        return result
    }
}

data class BacktestConfig(
    val lookbackMinutes: Int = 30,

    /** FRACTIONS */
    val takeProfit: Double = 0.03,
    val stopLoss: Double = 0.015,

    /** Costs (FRACTIONS) */
    val feePerSide: Double = BINANCE_FEE,
    val slippagePerSide: Double = 0.0002,

    val worstCaseIfBothHit: Boolean = true,
    val allowOverlappingTrades: Boolean = false,

    /** What you already started centralizing */
    val horizonMinutes: Int = 60,

    /** Candle size */
    val intervalMillis: Long = 60_000L,
)

data class EventStudyConfig(
    val negativeSampleEveryN: Int = 50,
    val seed: Int = 1337,

    val patternBars: Int = 3,
    val contextBars: Int = 30,

    val topK: Int = 30,
    val minPosCount: Int = 2,
    val maxNegatives: Int = 50_000,

    // ✅ quality gate (all config-driven)
    val minPosEventsToRun: Int = 50,          // after history filter
    val minNegSamplesToRun: Int = 2_000,      // after exclusions + cap
    val minDistinctFullKeysToRun: Int = 200,  // ensure we aren’t learning from tiny keyspace
)

data class SignalConfig(
    val ret30mMax: Double = -0.005,
    val volumeZMin: Double = 1.0,
    val contractionMin: Double = 1.0,
)

data class AlgoConfig(
    val profitGroup: ProfitGroupConfig = ProfitGroupConfig(),
    val backtest: BacktestConfig = BacktestConfig(),
    val eventStudy: EventStudyConfig = EventStudyConfig(),
    val signal: SignalConfig = SignalConfig(),
) {
    fun id(): String =
        "TP=${pct(backtest.takeProfit)} SL=${pct(backtest.stopLoss)} HZ=${backtest.horizonMinutes}m " +
                "DD=${pct(profitGroup.maxDrawdownAllowed)} LL=${profitGroup.localLowLookbackMinutes}m"

    private fun pct(x: Double): String = "%.2f%%".format(x * 100.0)
}

enum class ProfitGroupSort { TIME_ASC, GAIN_DESC, DRAWDOWN_ASC }
