package com.example.network.universe

import com.example.platform.model.SymbolLiquidity
import com.example.platform.model.Ticker
import com.example.platform.model.UniverseConfig

class UniverseRanker {
    fun rank(
        tickers: List<Ticker>,
        allowed: Set<String>,
        config: UniverseConfig
    ): List<SymbolLiquidity> {
        val include = config.includeSymbols
        val exclude = config.excludeSymbols

        val ranked = tickers
            .asSequence()
            .filter { it.symbol in allowed }
            .filter { include.isEmpty() || it.symbol in include }
            .filter { exclude.isEmpty() || it.symbol !in exclude }
            .map {
                SymbolLiquidity(
                    symbol = it.symbol,
                    quoteVolume = it.quoteVolume,
                    trades = it.tradeCount
                )
            }
            .filter { it.quoteVolume >= config.minQuoteVolume }
            .filter { it.trades >= config.minTrades }
            .sortedByDescending { it.quoteVolume }
            .toList()

        return if (config.maxSymbols > 0) ranked.take(config.maxSymbols) else ranked
    }
}
