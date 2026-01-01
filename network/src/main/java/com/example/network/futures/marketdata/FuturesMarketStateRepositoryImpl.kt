package com.example.network.futures.marketdata

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.futures.FuturesOrderBookAdapter
import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.network.interfaces.LiveAggTradeRepo
import com.example.network.interfaces.LiveBookTickerRepo
import com.example.network.interfaces.LiveDepthRepo
import com.example.network.marketstate.MarketStateRepositoryImpl
import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.Flow

internal class FuturesMarketStateRepositoryImpl(
    bookTickerRepo: LiveBookTickerRepo,
    depthRepo: LiveDepthRepo,
    tradeRepo: LiveAggTradeRepo,
    orderBookService: FuturesOrderBookService
) : FuturesMarketStateRepository {
    private val delegate = MarketStateRepositoryImpl(
        bookTickerRepo = bookTickerRepo,
        depthRepo = depthRepo,
        tradeRepo = tradeRepo,
        orderBookService = FuturesOrderBookAdapter(orderBookService)
    )

    override fun streamMarketState(
        symbols: List<Symbol>,
        config: MarketStateConfig
    ): Flow<MarketState> {
        return delegate.streamMarketState(symbols, config)
    }
}
