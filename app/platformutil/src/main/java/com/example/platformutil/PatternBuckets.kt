package com.example.platformutil

object PatternBuckets {
    fun bucketRet(ret: Double): String {
        return when {
            ret < -0.004 -> "DN2"
            ret < -0.0015 -> "DN1"
            ret < 0.0015 -> "FL"
            ret < 0.004 -> "UP1"
            else -> "UP2"
        }
    }

    fun bucketRangeRatio(ratio: Double): String {
        if (!ratio.isFinite() || ratio <= 0.0) return "N"
        return when {
            ratio < 0.80 -> "S"
            ratio < 1.25 -> "N"
            else -> "L"
        }
    }

    fun bucketVolumeRatio(ratio: Double): String {
        if (!ratio.isFinite() || ratio <= 0.0) return "n"
        return when {
            ratio < 0.80 -> "s"
            ratio < 1.25 -> "n"
            else -> "b"
        }
    }
}
