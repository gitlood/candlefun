package com.example.avellaneda.metrics

data class FillStats(
    var totalNotional: Double = 0.0,
    var totalFees: Double = 0.0,
    var makerCount: Int = 0,
    var takerCount: Int = 0
) {
    fun totalCount(): Int = makerCount + takerCount

    fun record(notional: Double, makerFeePct: Double, takerFeePct: Double, isMaker: Boolean) {
        totalNotional += notional
        if (isMaker) {
            makerCount++
            totalFees += notional * makerFeePct
        } else {
            takerCount++
            totalFees += notional * takerFeePct
        }
    }
}
