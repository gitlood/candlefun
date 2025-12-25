package com.example.network.universe

import com.example.network.interfaces.ExchangeInfoService
import com.example.network.interfaces.TickerService
import com.example.platform.model.MarketInfo
import com.example.platform.model.Ticker

class UniverseFetcher(
    private val exchangeInfoService: ExchangeInfoService,
    private val tickerService: TickerService
) {
    suspend fun fetchData(): Pair<MarketInfo, List<Ticker>> {
        val info = exchangeInfoService.getExchangeInfo()
        val tickers = tickerService.getTickers24hr()
        return info to tickers
    }
}
