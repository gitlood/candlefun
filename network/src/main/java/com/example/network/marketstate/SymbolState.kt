package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import kotlinx.coroutines.sync.Mutex

internal class SymbolState(config: MarketStateConfig) {
    val lock = Mutex()
    val orderBook = OrderBookTracker()
    val depthSync = DepthSyncState()
    val ofiWindow = RollingSumWindow(config.ofiWindow)
    val tradeWindow = RollingTradeWindow(config.tradeWindow)
    val volatility = RollingVolatility(config.volWindows)

    var lastBookTicker: WsBookTickerData? = null
    var lastTrade: WsAggTradeData? = null
    var lastBookEventTime: Long? = null
    var lastDepthEventTime: Long? = null
    var lastTradeEventTime: Long? = null
}
