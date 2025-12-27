package com.example.platform.model

import com.example.platform.model.enums.KlineInterval
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformModelsTest {
    @Test
    fun `models retain values`() {
        val bookLevel = BookLevel(price = 1.0, quantity = 2.0)
        val orderBook = OrderBook(
            lastUpdateId = 1L,
            bids = listOf(OrderBookEntry(1.0, 2.0)),
            asks = listOf(OrderBookEntry(1.1, 1.5))
        )
        val marketState = MarketState(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            eventTimeMs = 2L,
            bestBidPrice = 1.0,
            bestBidQty = 2.0,
            bestAskPrice = 1.1,
            bestAskQty = 1.5,
            midPrice = 1.05,
            spread = 0.1,
            microPrice = 1.05,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 1,
            tradeVolume1s = 1.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = 1.05,
            lastTradeQty = 0.5,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.01,
            vol5s = 0.02,
            vol10s = 0.03,
            vol1m = 0.04,
            vol5m = 0.05,
            bookUpdateId = 1L,
            bidLevels = listOf(bookLevel),
            askLevels = listOf(bookLevel)
        )
        val ticker = Ticker(symbol = "BTCUSDT", quoteVolume = 100.0, tradeCount = 10L, lastPrice = 1.05)
        val trade = Trade(tradeId = 1L, price = 1.05, quantity = 0.1, timestamp = 1L, isBuyerMaker = true)
        val kline = Kline(
            openTime = 1L,
            open = 1.0,
            high = 1.2,
            low = 0.9,
            close = 1.1,
            volume = 10.0,
            closeTime = 2L,
            quoteAssetVolume = 11.0,
            numberOfTrades = 5,
            takerBuyBaseAssetVolume = 6.0,
            takerBuyQuoteAssetVolume = 7.0
        )
        val liveCandle = LiveCandle(openTime = 1L, open = 1.0, high = 1.2, low = 0.9, close = 1.1)
        val candleHistory = CandleHistoryItem(openTime = 1L, open = 1.0, high = 1.2, low = 0.9, close = 1.1, volume = 10.0)
        val liquidity = SymbolLiquidity(symbol = "BTCUSDT", quoteVolume = 100.0, trades = 10L)
        val marketInfo = MarketInfo(
            symbols = listOf(MarketSymbol("BTCUSDT", "TRADING", "USDT", true, listOf("SPOT")))
        )
        val universe = UniverseConfig(quoteAssets = setOf("USDT"), maxSymbols = 5)
        val account = Account(
            makerCommission = 1,
            takerCommission = 2,
            buyerCommission = 3,
            sellerCommission = 4,
            canTrade = true,
            canWithdraw = true,
            canDeposit = true,
            updateTime = 1L,
            accountType = "SPOT",
            balances = listOf(AccountBalance("USDT", 1.0, 0.0))
        )
        val balance = Balance(asset = "USDT", free = "1", locked = "0")

        assertEquals(1.0, orderBook.bids.first().price)
        assertEquals("BTCUSDT", marketState.symbol)
        assertEquals(10L, ticker.tradeCount)
        assertEquals(1L, trade.tradeId)
        assertEquals(1L, kline.openTime)
        assertEquals(1L, liveCandle.openTime)
        assertEquals(1L, candleHistory.openTime)
        assertEquals("BTCUSDT", liquidity.symbol)
        assertEquals(1, account.makerCommission)
        assertEquals("USDT", balance.asset)
        assertEquals("TRADING", marketInfo.symbols.first().status)
        assertEquals(5, universe.maxSymbols)
    }

    @Test
    fun `enums expose expected values`() {
        assertEquals("BUY", OrderSide.BUY.name)
        assertEquals("LIMIT", OrderType.LIMIT.name)
        assertEquals("1m", KlineInterval.ONE_MINUTE.value)
    }
}
