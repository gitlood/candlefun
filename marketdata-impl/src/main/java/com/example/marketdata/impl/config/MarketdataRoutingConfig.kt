package com.example.marketdata.impl.config

enum class MarketdataSource {
    SPOT,
    FUTURES
}

data class MarketdataRoutingConfig(
    val source: MarketdataSource = MarketdataSource.SPOT
) {
    companion object {
        fun default(): MarketdataRoutingConfig {
            val env = System.getenv("MARKETDATA_SOURCE")?.uppercase()
            val source = when (env) {
                "FUTURES" -> MarketdataSource.FUTURES
                else -> MarketdataSource.SPOT
            }
            return MarketdataRoutingConfig(source)
        }
    }
}
