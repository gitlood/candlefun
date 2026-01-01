package com.example.marketdata.impl.di

import com.example.marketdata.impl.config.MarketdataRoutingConfig
import com.example.marketdata.impl.config.MarketdataSource
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import org.koin.dsl.module

val marketdataRoutingModule = module {
    single { MarketdataRoutingConfig.default() }

    single<MarketStateRepository> {
        val config = get<MarketdataRoutingConfig>()
        when (config.source) {
            MarketdataSource.SPOT -> get()
            MarketdataSource.FUTURES -> FuturesMarketStateRepositoryAdapter(get())
        }
    }
}

private class FuturesMarketStateRepositoryAdapter(
    private val futures: FuturesMarketStateRepository
) : MarketStateRepository {
    override fun streamMarketState(symbols: List<Symbol>, config: MarketStateConfig) =
        futures.streamMarketState(symbols, config)
}
