package com.example.marketdata.impl.di

import com.example.marketdata.impl.MarketdataImplConfig
import com.example.marketdata.impl.SqliteCandleHistoryRepository
import com.example.marketdata.repository.CandleHistoryRepository
import com.example.marketdata.usecase.GetCandlesForDaysUseCase
import org.koin.dsl.module

val marketdataImplModule = module {
    single { MarketdataImplConfig.default() }

    single<CandleHistoryRepository> {
        val config = get<MarketdataImplConfig>()
        SqliteCandleHistoryRepository(jdbcUrl = config.jdbcUrl, interval = config.interval)
    }

    factory { GetCandlesForDaysUseCase(get()) }
}
