package com.example.marketdata.impl.di

import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import org.koin.dsl.module

val marketdataFactoryModule = module {
    factory<MarketStateRepository> { get() }
    factory<FuturesMarketStateRepository> { get() }
}
