package com.example.network.model

import com.example.platformutil.model.Candle

fun KlineResponse.toCandle(): Candle = Candle(
    openTime = openTime,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    closeTime = closeTime,
    quoteAssetVolume = quoteAssetVolume,
    numberOfTrades = numberOfTrades,
    takerBuyBaseAssetVolume = takerBuyBaseAssetVolume,
    takerBuyQuoteAssetVolume = takerBuyQuoteAssetVolume
)
