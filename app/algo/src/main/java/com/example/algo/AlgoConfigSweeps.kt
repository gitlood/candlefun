package com.example.algo

import com.example.platformutil.AlgoConfig

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
         * Example: tp=0.02 -> [0.04, 0.03, 0.02] after sort desc.
         */
        val profitGroupThresholdMultipliers: DoubleArray = doubleArrayOf(2.0, 1.5, 1.0),

        /**
         * Simple horizon sanity: bigger TP needs more minutes.
         * You can tune this table anytime.
         */
        val minHorizonByTp: List<Pair<Double, Int>> = listOf(
            0.02 to 30,
            0.03 to 60,
            0.05 to 120,
        ),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SweepConstraints

            if (requireTpGreaterThanSl != other.requireTpGreaterThanSl) return false
            if (minRiskReward != other.minRiskReward) return false
            if (maxRiskReward != other.maxRiskReward) return false
            if (tieProfitGroupThresholdsToTp != other.tieProfitGroupThresholdsToTp) return false
            if (!profitGroupThresholdMultipliers.contentEquals(other.profitGroupThresholdMultipliers)) return false
            if (minHorizonByTp != other.minHorizonByTp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = requireTpGreaterThanSl.hashCode()
            result = 31 * result + minRiskReward.hashCode()
            result = 31 * result + maxRiskReward.hashCode()
            result = 31 * result + tieProfitGroupThresholdsToTp.hashCode()
            result = 31 * result + profitGroupThresholdMultipliers.contentHashCode()
            result = 31 * result + minHorizonByTp.hashCode()
            return result
        }
    }

    fun grid(
        base: AlgoConfig = AlgoConfig(),
        takeProfits: List<Double> = listOf(0.02, 0.03, 0.05),
        stopLosses: List<Double> = listOf(0.01, 0.015, 0.02),
        horizonsMinutes: List<Int> = listOf(30, 60, 120),
        maxDrawdowns: List<Double> = listOf(0.10, 0.20, 0.30),
        localLowLookbacks: List<Int> = listOf(0, 5, 10),
        requireContinuous: List<Boolean> = listOf(false),
        dedupeOverlapping: List<Boolean> = listOf(true),
        constraints: SweepConstraints = SweepConstraints(),
    ): Sequence<AlgoConfig> = sequence {

        fun minHorizonFor(tp: Double): Int {
            // pick the largest rule that matches (so tp=0.05 -> 120)
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
                                for (dedupe in dedupeOverlapping) {

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
                                            )
                                        )
                                    )
                                }
    }
}
