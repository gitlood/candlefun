package com.example.historicaldata

import org.jetbrains.exposed.sql.Table

object Candles : Table() {
    val openTime = long("open_time").uniqueIndex()
    val open = text("open")
    val high = text("high")
    val low = text("low")
    val close = text("close")
    val volume = text("volume")
    val closeTime = long("close_time")
    val quoteAssetVolume = text("quote_asset_volume")
    val numberOfTrades = integer("number_of_trades")
    val takerBuyBaseAssetVolume = text("taker_buy_base_asset_volume")
    val takerBuyQuoteAssetVolume = text("taker_buy_quote_asset_volume")
}
