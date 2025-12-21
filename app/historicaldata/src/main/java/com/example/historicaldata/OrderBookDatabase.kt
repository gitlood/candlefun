package com.example.historicaldata

import org.jetbrains.exposed.sql.Table

object OrderBookSnapshots : Table() {
    val timestamp = long("timestamp")
    val symbol = text("symbol")
    val bestBid = double("best_bid")
    val bestAsk = double("best_ask")
    val midPrice = double("mid_price")
    val spread = double("spread")
    val bidDepth10 = double("bid_depth_10")
    val askDepth10 = double("ask_depth_10")
    val imbalance10 = double("imbalance_10")
    val bidDepth20 = double("bid_depth_20")
    val askDepth20 = double("ask_depth_20")
    val imbalance20 = double("imbalance_20")
    val updateId = long("update_id")

    init {
        index(true, symbol, timestamp)
    }
}
