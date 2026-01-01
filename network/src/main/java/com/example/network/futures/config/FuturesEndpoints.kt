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
        fun usdMTest(): FuturesEndpoints = FuturesEndpoints(
            restBase = BinanceEnvs.USD_M_FUTURES_TESTNET.restBase,
            wsBase = BinanceEnvs.USD_M_FUTURES_TESTNET.wsBase
        )
        fun custom(restBase: String, wsBase: String): FuturesEndpoints =
            FuturesEndpoints(restBase = restBase, wsBase = wsBase)

        fun deduced(restBase: String): String? {
            return when {
                restBase.startsWith("https://") -> restBase.replaceFirst("https://", "wss://").trimEnd('/') + "/stream"
                restBase.startsWith("http://") -> restBase.replaceFirst("http://", "ws://").trimEnd('/') + "/stream"
                else -> null
            }
        }
    }
}
