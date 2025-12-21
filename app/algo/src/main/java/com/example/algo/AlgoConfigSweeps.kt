package com.example.algo

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.SignalConfig

object AlgoConfigSweeps {

    data class SweepConstraints(
        val requireTpGreaterThanSl: Boolean = true,
        val minRiskReward: Double = 1.3,
        val maxRiskReward: Double = 6.0,

        /**
         * If true, sets profitGroup.thresholds from the swept TP so
         * profit-group discovery + event study + pattern backtest are aligned.
         */
        val tieProfitGroupThresholdsToTp: Boolean = true,

        /**
         * Multipliers used when tieProfitGroupThresholdsToTp = true.
         * Example: tp=0.006 -> [0.012, 0.009, 0.006] after sort desc.
         */
        val profitGroupThresholdMultipliers: DoubleArray = doubleArrayOf(2.0, 1.5, 1.0),

        /**
         * Simple horizon sanity: bigger TP needs more minutes.
         */
        val minHorizonByTp: List<Pair<Double, Int>> = listOf(
            0.004 to 20,
            0.005 to 30,
            0.006 to 40,
        ),
    )

    fun grid(
        base: AlgoConfig = AlgoConfig(
            backtest = BacktestConfig(
                intervalMillis = 5 * 60_000L, // 5-minute candles
                lookbackMinutes = 60,
                horizonMinutes = 40 // base, overwritten by sweep
            )
        ),

        // Backtest knobs
        takeProfits: List<Double> = listOf(0.004, 0.005, 0.006),
        stopLosses: List<Double> = listOf(0.002, 0.0025, 0.003),
        horizonsMinutes: List<Int> = listOf(20, 30, 40, 50),
        maxDrawdowns: List<Double> = listOf(0.02, 0.03, 0.04, 0.05),
        localLowLookbacks: List<Int> = listOf(0, 1, 2, 3),
        requireContinuous: List<Boolean> = listOf(true),
        dedupeOverlapping: List<Boolean> = listOf(true),

        // RuleGate knobs (THIS is how you get “trade a day”)
        ret30mMins: List<Double> = listOf(-0.02, -0.01, -0.005),
        volumeZMins: List<Double> = listOf(-1.0, -0.5, 0.0),
        contractionMaxes: List<Double> = listOf(1.05, 1.10, 1.20),
        trendSlopeMins: List<Double> = listOf(0.0),

        // Order book gate knobs
        orderBookEnableds: List<Boolean> = listOf(false),
        orderBookMinImbalance10s: List<Double> = listOf(0.05),
        orderBookMaxSpreadBps: List<Double> = listOf(15.0),

        constraints: SweepConstraints = SweepConstraints()
    ): Sequence<AlgoConfig> = sequence {

        fun minHorizonFor(tp: Double): Int {
            var m = 0
            for ((tpCutoff, minHz) in constraints.minHorizonByTp) {
                if (tp >= tpCutoff) m = maxOf(m, minHz)
            }
            return m
        }

        fun thresholdsFromTp(tp: Double): DoubleArray {
            val raw = constraints.profitGroupThresholdMultipliers
                .map { tp * it }
                .distinct()
                .sortedDescending()
            return raw.toDoubleArray()
        }

        fun isSane(tp: Double, sl: Double, hz: Int): Boolean {
            if (tp <= 0.0 || sl <= 0.0 || hz <= 0) return false
            if (constraints.requireTpGreaterThanSl && tp <= sl) return false
            val rr = tp / sl
            if (rr < constraints.minRiskReward || rr > constraints.maxRiskReward) return false
            if (hz < minHorizonFor(tp)) return false
            return true
        }

        for (tp in takeProfits)
            for (sl in stopLosses)
                for (hz in horizonsMinutes)
                    for (dd in maxDrawdowns)
                        for (ll in localLowLookbacks)
                            for (rc in requireContinuous)
                                for (dedupe in dedupeOverlapping)
                                    for (r30 in ret30mMins)
                                        for (vz in volumeZMins)
                                            for (cx in contractionMaxes)
                                                for (slope in trendSlopeMins)
                                                    for (obEnabled in orderBookEnableds)
                                                        for (obImb in orderBookMinImbalance10s)
                                                            for (obSpr in orderBookMaxSpreadBps) {

                                                    if (!isSane(tp, sl, hz)) continue

                                                    val pgThresholds =
                                                        if (constraints.tieProfitGroupThresholdsToTp) thresholdsFromTp(tp)
                                                        else base.profitGroup.thresholds

                                                    yield(
                                                        base.copy(
                                                            backtest = base.backtest.copy(
                                                                takeProfit = tp,
                                                                stopLoss = sl,
                                                                horizonMinutes = hz,
                                                            ),
                                                            profitGroup = base.profitGroup.copy(
                                                                thresholds = pgThresholds,
                                                                maxDrawdownAllowed = dd,
                                                                localLowLookbackMinutes = ll,
                                                                requireContinuous = rc,
                                                                dedupeOverlapping = dedupe,
                                                            ),
                                                            signal = SignalConfig(
                                                                ret30mMin = r30,
                                                                volumeZMin = vz,
                                                                contractionMax = cx,
                                                                trendSlopeMin = slope
                                                            ),
                                                            orderBook = base.orderBook.copy(
                                                                enabled = obEnabled,
                                                                minImbalance10 = obImb,
                                                                maxSpreadBps = obSpr
                                                            )
                                                        )
                                                    )
                                                }
    }
}
