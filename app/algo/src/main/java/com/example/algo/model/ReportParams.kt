package com.example.algo.model

data class ReportParams(
    val horizonMinutes: Int,
    val horizonBars: Int,
    val thresholdsPct: DoubleArray,
    val localLowLookBackMinutes: Int,
    val lookBackBars: Int,
    val requireContinuous: Boolean,
    val dedupeOverlappingWindows: Boolean,
    val sortBy: String,
    val intervalMillis: Long,
    val maxGroupsToPrint: Int,
    val maxDrawdownPctAllowed: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReportParams

        if (horizonMinutes != other.horizonMinutes) return false
        if (horizonBars != other.horizonBars) return false
        if (localLowLookBackMinutes != other.localLowLookBackMinutes) return false
        if (lookBackBars != other.lookBackBars) return false
        if (requireContinuous != other.requireContinuous) return false
        if (dedupeOverlappingWindows != other.dedupeOverlappingWindows) return false
        if (intervalMillis != other.intervalMillis) return false
        if (maxGroupsToPrint != other.maxGroupsToPrint) return false
        if (maxDrawdownPctAllowed != other.maxDrawdownPctAllowed) return false
        if (!thresholdsPct.contentEquals(other.thresholdsPct)) return false
        if (sortBy != other.sortBy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = horizonMinutes
        result = 31 * result + horizonBars
        result = 31 * result + localLowLookBackMinutes
        result = 31 * result + lookBackBars
        result = 31 * result + requireContinuous.hashCode()
        result = 31 * result + dedupeOverlappingWindows.hashCode()
        result = 31 * result + intervalMillis.hashCode()
        result = 31 * result + maxGroupsToPrint
        result = 31 * result + maxDrawdownPctAllowed.hashCode()
        result = 31 * result + thresholdsPct.contentHashCode()
        result = 31 * result + sortBy.hashCode()
        return result
    }
}
