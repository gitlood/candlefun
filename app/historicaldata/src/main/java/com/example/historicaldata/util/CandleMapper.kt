package com.example.historicaldata.util

import com.example.historicaldata.Candles
import com.example.platformutil.model.Candle
import org.jetbrains.exposed.sql.ResultRow

/**
 * Helper function to map a database [ResultRow] to a [Candle] object.
 */
internal fun toCandle(row: ResultRow): Candle = Candle(
    openTime = row[Candles.openTime],
    open = row[Candles.open],
    high = row[Candles.high],
    low = row[Candles.low],
    close = row[Candles.close],
    volume = row[Candles.volume],
    closeTime = row[Candles.closeTime],
    quoteAssetVolume = row[Candles.quoteAssetVolume],
    numberOfTrades = row[Candles.numberOfTrades],
    takerBuyBaseAssetVolume = row[Candles.takerBuyBaseAssetVolume],
    takerBuyQuoteAssetVolume = row[Candles.takerBuyQuoteAssetVolume]
)