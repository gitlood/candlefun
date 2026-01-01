package com.example.survivor

data class SurvivorGateStats(
    var total: Long = 0,
    var rejectedVol: Long = 0,
    var rejectedSpread: Long = 0,
    var rejectedOi: Long = 0,
    var flatFunding: Long = 0,
    var entries: Long = 0,
    var exits: Long = 0
) {
    fun report(): String {
        return "survivor gates: total=$total vol=$rejectedVol spread=$rejectedSpread " +
            "oiJump=$rejectedOi flatFunding=$flatFunding entries=$entries exits=$exits"
    }
}
