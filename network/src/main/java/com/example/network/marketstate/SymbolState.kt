package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import kotlinx.coroutines.sync.Mutex

internal class SymbolState(config: MarketStateConfig) {
    val lock = Mutex()
    val orderBook = OrderBookTracker()
    val ofiWindow = RollingSumWindow(config.ofiWindowMs)
    val tradeWindow = RollingTradeWindow(config.tradeWindowMs)
    val volatility = RollingVolatility(config.volWindowsMs)

    var lastBookTicker: WsBookTickerData? = null
    var lastTrade: WsAggTradeData? = null
    var lastBookEventTime: Long? = null
    var lastDepthEventTime: Long? = null
    var lastTradeEventTime: Long? = null
}
