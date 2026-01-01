package com.example.network.config

data class BinanceEndpoints(
    val restBase: String,
    val wsBase: String
)

object BinanceEnvs {
    val SPOT = BinanceEndpoints(
        restBase = "https://api.binance.com/api/v3",
        wsBase = "wss://stream.binance.com:9443/stream"
    )
    val TESTNET = BinanceEndpoints(
        restBase = "https://testnet.binance.vision/api/v3",
        wsBase = "wss://testnet.binance.vision/stream" 
    )
    val USD_M_FUTURES = BinanceEndpoints(
        restBase = "https://fapi.binance.com",
        wsBase = "wss://fstream.binance.com/stream"
    )
    val USD_M_FUTURES_TESTNET = BinanceEndpoints(
        restBase = "https://testnet.binancefuture.com",
        wsBase = "wss://stream.binancefuture.com/stream"
    )
}
