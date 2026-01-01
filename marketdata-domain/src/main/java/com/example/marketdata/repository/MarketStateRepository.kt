package com.example.marketdata.repository

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.Flow

interface MarketStateRepository {
    fun streamMarketState(symbols: List<Symbol>, config: MarketStateConfig = MarketStateConfig()): Flow<MarketState>
}
