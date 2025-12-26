package com.example.network.futures.config

import com.example.network.config.BinanceEnvs


data class FuturesEndpoints(
    val restBase: String,
    val wsBase: String
) {
    companion object {
        fun usdM(): FuturesEndpoints = FuturesEndpoints(
            restBase = BinanceEnvs.USD_M_FUTURES.restBase,
            wsBase = BinanceEnvs.USD_M_FUTURES.wsBase
        )
    }
}
