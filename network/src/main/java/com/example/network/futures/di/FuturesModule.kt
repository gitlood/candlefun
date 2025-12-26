package com.example.network.futures.di

import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.futures.FuturesOrderBookServiceImpl
import com.example.network.futures.FuturesUserDataServiceImpl
import com.example.network.futures.FuturesWebSocketServiceImpl
import com.example.network.futures.config.FuturesEndpoints
import com.example.network.futures.interfaces.FuturesLiveAggTradeRepo
import com.example.network.futures.interfaces.FuturesLiveBookTickerRepo
import com.example.network.futures.interfaces.FuturesLiveDepthRepo
import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.network.futures.marketdata.FuturesLiveAggTradeRepoImpl
import com.example.network.futures.marketdata.FuturesLiveBookTickerRepoImpl
import com.example.network.futures.marketdata.FuturesLiveDepthRepoImpl
import com.example.network.futures.marketdata.FuturesMarketStateRepositoryImpl
import org.koin.dsl.module

val futuresModule = module {
    single { FuturesEndpoints.usdM() }

    single<FuturesWebSocketService> { FuturesWebSocketServiceImpl(get(), get()) }
    single<FuturesOrderBookService> { FuturesOrderBookServiceImpl(get(), get()) }

    single<FuturesLiveBookTickerRepo> { FuturesLiveBookTickerRepoImpl(get()) }
    single<FuturesLiveDepthRepo> { FuturesLiveDepthRepoImpl(get()) }
    single<FuturesLiveAggTradeRepo> { FuturesLiveAggTradeRepoImpl(get()) }

    single<FuturesMarketStateRepository> { FuturesMarketStateRepositoryImpl(get(), get(), get(), get()) }

    factory<FuturesUserDataService> {
        val apiKey = System.getenv("BINANCE_FUTURES_API_KEY")
            ?: error("BINANCE_FUTURES_API_KEY is required for futures user data stream")
        FuturesUserDataServiceImpl(get(), get(), apiKey)
    }
}
