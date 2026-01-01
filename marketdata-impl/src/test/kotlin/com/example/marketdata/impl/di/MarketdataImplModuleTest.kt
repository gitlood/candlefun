package com.example.marketdata.impl.di

import com.example.marketdata.impl.MarketdataImplConfig
import com.example.marketdata.repository.CandleHistoryRepository
import com.example.marketdata.usecase.GetCandlesForDaysUseCase
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class MarketdataImplModuleTest : KoinComponent {

    @Test
    fun `module provides config repo and usecase`() {
        startKoin { modules(marketdataImplModule) }
        try {
            assertNotNull(get<MarketdataImplConfig>())
            assertNotNull(get<CandleHistoryRepository>())
            assertNotNull(get<GetCandlesForDaysUseCase>())
        } finally {
            stopKoin()
        }
    }
}
