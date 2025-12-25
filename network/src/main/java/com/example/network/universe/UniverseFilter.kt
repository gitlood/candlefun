package com.example.network.universe

import com.example.platform.model.MarketInfo
import com.example.platform.model.UniverseConfig

class UniverseFilter {
    fun filterAllowed(
        info: MarketInfo,
        config: UniverseConfig
    ): Set<String> {
        return info.symbols
            .filter { symbol ->
                // status is non-null in domain model (defaults to "UNKNOWN" if missing in DTO)
                val statusOk = symbol.status == "TRADING"
                val quoteOk = symbol.quoteAsset.uppercase() in config.quoteAssets
                val permissions = symbol.permissions
                val spotOk = symbol.isSpotTradingAllowed &&
                    (permissions.isEmpty() || permissions.contains("SPOT"))
                statusOk && quoteOk && spotOk
            }
            .map { it.symbol }
            .toSet()
    }
}
