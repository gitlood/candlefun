package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsDepthUpdateData
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.LiveAggTradeRepo
import com.example.network.interfaces.LiveBookTickerRepo
import com.example.network.interfaces.LiveDepthRepo
import com.example.platform.model.OrderBook
import com.example.platform.model.OrderBookEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketStateRepositoryImplTest {

    @Test
    fun `streamMarketState emits snapshots`() = runBlocking {
        val bookTickerRepo = object : LiveBookTickerRepo {
            override fun streamBookTickers(symbols: List<String>): Flow<WsBookTickerData> = flow {
                emit(
                    WsBookTickerData(
                        symbol = "BTCUSDT",
                        eventTime = 1_000L,
                        updateId = 1L,
                        bestBidPrice = "100.0",
                        bestBidQty = "2.0",
                        bestAskPrice = "101.0",
                        bestAskQty = "2.0"
                    )
                )
            }
        }
        val depthRepo = object : LiveDepthRepo {
            override fun streamDepthUpdates(symbols: List<String>, speedMs: Int): Flow<WsDepthUpdateData> = flow {
                emit(
                    WsDepthUpdateData(
                        symbol = "BTCUSDT",
                        eventTime = 1_100L,
                        firstUpdateId = 1,
                        finalUpdateId = 1,
                        bids = listOf(listOf("100.0", "1.0")),
                        asks = listOf(listOf("101.0", "1.0"))
                    )
                )
            }
        }
        val tradeRepo = object : LiveAggTradeRepo {
            override fun streamAggTrades(symbols: List<String>): Flow<WsAggTradeData> = flow {
                emit(
                    WsAggTradeData(
                        symbol = "BTCUSDT",
                        eventTime = 1_200L,
                        aggTradeId = 1,
                        price = "100.5",
                        quantity = "0.2",
                        firstTradeId = 1,
                        lastTradeId = 1,
                        tradeTime = 1_200L,
                        isBuyerMaker = false
                    )
                )
            }
        }
        val orderBookService = object : BinanceOrderBookService {
            override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
                return OrderBook(
                    lastUpdateId = 10,
                    bids = listOf(OrderBookEntry(99.0, 1.0)),
                    asks = listOf(OrderBookEntry(102.0, 1.0))
                )
            }
        }

        val repo = MarketStateRepositoryImpl(
            bookTickerRepo = bookTickerRepo,
            depthRepo = depthRepo,
            tradeRepo = tradeRepo,
            orderBookService = orderBookService,
            clockMs = { 2_000L }
        )
        val config = MarketStateConfig(tickMs = 1L, depthLevels = 1)

        val state = withTimeout(2_000L) {
            repo.streamMarketState(listOf("btcusdt"), config).first()
        }

        assertEquals("BTCUSDT", state.symbol)
    }
}
