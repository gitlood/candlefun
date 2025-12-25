package com.example.network.mapper

import com.example.network.dto.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DtoMappersTest {

    @Test
    fun `KlineDto toDomain maps correctly`() {
        val dto = KlineDto(
            openTime = 1000L,
            open = "100.0",
            high = "105.0",
            low = "95.0",
            close = "102.0",
            volume = "500.0",
            closeTime = 2000L,
            quoteAssetVolume = "50000.0",
            numberOfTrades = 10,
            takerBuyBaseAssetVolume = "200.0",
            takerBuyQuoteAssetVolume = "20000.0",
            ignore = "0"
        )
        val domain = dto.toDomain()

        assertEquals(1000L, domain.openTime)
        assertEquals(100.0, domain.open, 0.0)
        assertEquals(105.0, domain.high, 0.0)
        assertEquals(95.0, domain.low, 0.0)
        assertEquals(102.0, domain.close, 0.0)
        assertEquals(500.0, domain.volume, 0.0)
        assertEquals(2000L, domain.closeTime)
        assertEquals(50000.0, domain.quoteAssetVolume, 0.0)
        assertEquals(10, domain.numberOfTrades)
        assertEquals(200.0, domain.takerBuyBaseAssetVolume, 0.0)
        assertEquals(20000.0, domain.takerBuyQuoteAssetVolume, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `KlineDto toDomain throws on invalid doubles`() {
        val dto = KlineDto(
            openTime = 1000L,
            open = "invalid",
            high = "invalid",
            low = "invalid",
            close = "invalid",
            volume = "invalid",
            closeTime = 2000L,
            quoteAssetVolume = "invalid",
            numberOfTrades = 10,
            takerBuyBaseAssetVolume = "invalid",
            takerBuyQuoteAssetVolume = "invalid",
            ignore = "0"
        )
        dto.toDomain()
    }

    @Test
    fun `Ticker24HrDto toDomain maps correctly`() {
        val dto = Ticker24HrDto(
            symbol = "BTCUSDT",
            quoteVolume = "1000.0",
            count = 500,
            lastPrice = "50000.0"
        )
        val domain = dto.toDomain()

        assertEquals("BTCUSDT", domain.symbol)
        assertEquals(1000.0, domain.quoteVolume, 0.0)
        assertEquals(500, domain.tradeCount)
        assertEquals(50000.0, domain.lastPrice, 0.0)
    }

    @Test
    fun `AggTradeDto toDomain maps correctly`() {
        val dto = AggTradeDto(
            tradeId = 123L,
            price = "50000.0",
            quantity = "1.5",
            timestamp = 1000L,
            isBuyerMaker = true
        )
        val domain = dto.toDomain()

        assertEquals(123L, domain.tradeId)
        assertEquals(50000.0, domain.price, 0.0)
        assertEquals(1.5, domain.quantity, 0.0)
        assertEquals(1000L, domain.timestamp)
        assertEquals(true, domain.isBuyerMaker)
    }

    @Test
    fun `ExchangeInfoDto toDomain maps correctly`() {
        val symbolDto = ExchangeSymbolDto(
            symbol = "BTCUSDT",
            status = "TRADING",
            quoteAsset = "USDT",
            isSpotTradingAllowed = true,
            permissions = listOf("SPOT")
        )
        val dto = ExchangeInfoDto(symbols = listOf(symbolDto))
        val domain = dto.toDomain()

        assertEquals(1, domain.symbols.size)
        val domainSymbol = domain.symbols[0]
        assertEquals("BTCUSDT", domainSymbol.symbol)
        assertEquals("TRADING", domainSymbol.status)
        assertEquals("USDT", domainSymbol.quoteAsset)
        assertEquals(true, domainSymbol.isSpotTradingAllowed)
        assertEquals(listOf("SPOT"), domainSymbol.permissions)
    }

    @Test
    fun `ExchangeSymbolDto toDomain handles nulls`() {
        val dto = ExchangeSymbolDto(
            symbol = "BTCUSDT",
            status = null,
            quoteAsset = null,
            isSpotTradingAllowed = null,
            permissions = null
        )
        val domain = dto.toDomain()

        assertEquals("BTCUSDT", domain.symbol)
        assertEquals("UNKNOWN", domain.status)
        assertEquals("", domain.quoteAsset)
        assertEquals(false, domain.isSpotTradingAllowed)
        assertEquals(emptyList<String>(), domain.permissions)
    }

    @Test
    fun `OrderBookDto toDomain maps correctly`() {
        val bid = OrderBookEntryDto("100.0", "1.0")
        val ask = OrderBookEntryDto("101.0", "2.0")
        val dto = OrderBookDto(
            lastUpdateId = 12345L,
            bids = listOf(bid),
            asks = listOf(ask)
        )
        val domain = dto.toDomain()

        assertEquals(12345L, domain.lastUpdateId)
        assertEquals(1, domain.bids.size)
        assertEquals(100.0, domain.bids[0].price, 0.0)
        assertEquals(1.0, domain.bids[0].quantity, 0.0)
        assertEquals(1, domain.asks.size)
        assertEquals(101.0, domain.asks[0].price, 0.0)
        assertEquals(2.0, domain.asks[0].quantity, 0.0)
    }

    @Test
    fun `AccountInfoDto toDomain maps correctly`() {
        val balance = BalanceDto("BTC", "1.0", "0.5")
        val dto = AccountInfoDto(
            makerCommission = 10,
            takerCommission = 10,
            buyerCommission = 0,
            sellerCommission = 0,
            canTrade = true,
            canWithdraw = true,
            canDeposit = true,
            updateTime = 1000L,
            accountType = "SPOT",
            balances = listOf(balance)
        )
        val domain = dto.toDomain()

        assertEquals(10, domain.makerCommission)
        assertEquals(true, domain.canTrade)
        assertEquals(1000L, domain.updateTime)
        assertEquals("SPOT", domain.accountType)
        assertEquals(1, domain.balances.size)
        assertEquals("BTC", domain.balances[0].asset)
        assertEquals(1.0, domain.balances[0].free, 0.0)
        assertEquals(0.5, domain.balances[0].locked, 0.0)
    }

    @Test
    fun `TradeResponseDto toDomain maps correctly`() {
        val dto = TradeResponseDto(
            symbol = "BTCUSDT",
            orderId = 123L,
            clientOrderId = "client123",
            transactTime = 1000L,
            price = "50000.0",
            origQty = "1.0",
            executedQty = "1.0",
            cummulativeQuoteQty = "50000.0",
            status = "FILLED",
            timeInForce = "GTC",
            type = "LIMIT",
            side = "BUY"
        )
        val domain = dto.toDomain()

        assertEquals("BTCUSDT", domain.symbol)
        assertEquals(123L, domain.orderId)
        assertEquals("client123", domain.clientOrderId)
        assertEquals(1000L, domain.transactTime)
        assertEquals(50000.0, domain.price, 0.0)
        assertEquals(1.0, domain.origQty, 0.0)
        assertEquals(1.0, domain.executedQty, 0.0)
        assertEquals(50000.0, domain.cummulativeQuoteQty, 0.0)
        assertEquals("FILLED", domain.status)
        assertEquals("GTC", domain.timeInForce)
        assertEquals("LIMIT", domain.type)
        assertEquals("BUY", domain.side)
    }
}
