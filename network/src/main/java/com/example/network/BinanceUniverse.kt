package com.example.network

import com.example.platform.model.SymbolLiquidity
import com.example.platform.model.UniverseConfig
import com.example.network.universe.UniverseFetcher
import com.example.network.universe.UniverseFilter
import com.example.network.universe.UniverseRanker

class BinanceUniverse(
    private val fetcher: UniverseFetcher,
    private val filter: UniverseFilter,
    private val ranker: UniverseRanker
) {
    suspend fun fetchTopSymbols(config: UniverseConfig): List<SymbolLiquidity> {
        // 1. Fetch
        val (info, tickers) = fetcher.fetchData()

        // 2. Filter
        val allowedSymbols = filter.filterAllowed(info, config)

        // 3. Rank
        return ranker.rank(tickers, allowedSymbols, config)
    }
}
