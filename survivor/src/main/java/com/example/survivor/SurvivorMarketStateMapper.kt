package com.example.survivor

import com.example.platform.model.MarketState

fun SurvivorSnapshot.asMarketState(): MarketState {
    return MarketState(
        symbol = symbol,
        timestampMs = timestampMs,
        eventTimeMs = timestampMs,
        bestBidPrice = null,
        bestBidQty = null,
        bestAskPrice = null,
        bestAskQty = null,
        midPrice = markPrice,
        spread = null,
        microPrice = null,
        depthImbalance = null,
        ofi1s = 0.0,
        tradeCount1s = 0,
        tradeVolume1s = 0.0,
        tradeImbalance1s = 0.0,
        lastTradePrice = null,
        lastTradeQty = null,
        lastTradeIsBuyerMaker = null,
        vol1s = null,
        vol5s = null,
        vol10s = null,
        vol1m = null,
        vol5m = null,
        bookUpdateId = 0L,
        bidLevels = emptyList(),
        askLevels = emptyList()
    )
}
