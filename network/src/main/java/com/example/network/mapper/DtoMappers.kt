package com.example.network.mapper

import com.example.network.dto.*
import com.example.platform.model.*

fun KlineDto.toDomain(): Kline = Kline(
    openTime = openTime,
    open = parseDoubleStrict(open, "kline.open"),
    high = parseDoubleStrict(high, "kline.high"),
    low = parseDoubleStrict(low, "kline.low"),
    close = parseDoubleStrict(close, "kline.close"),
    volume = parseDoubleStrict(volume, "kline.volume"),
    closeTime = closeTime,
    quoteAssetVolume = parseDoubleStrict(quoteAssetVolume, "kline.quoteAssetVolume"),
    numberOfTrades = numberOfTrades,
    takerBuyBaseAssetVolume = parseDoubleStrict(takerBuyBaseAssetVolume, "kline.takerBuyBaseAssetVolume"),
    takerBuyQuoteAssetVolume = parseDoubleStrict(takerBuyQuoteAssetVolume, "kline.takerBuyQuoteAssetVolume")
)

fun Ticker24HrDto.toDomain(): Ticker = Ticker(
    symbol = symbol,
    quoteVolume = parseDoubleStrict(quoteVolume, "ticker.quoteVolume"),
    tradeCount = count,
    lastPrice = parseDoubleStrict(lastPrice, "ticker.lastPrice")
)

fun AggTradeDto.toDomain(): Trade = Trade(
    tradeId = tradeId,
    price = parseDoubleStrict(price, "aggTrade.price"),
    quantity = parseDoubleStrict(quantity, "aggTrade.quantity"),
    timestamp = timestamp,
    isBuyerMaker = isBuyerMaker
)

fun ExchangeInfoDto.toDomain(): MarketInfo = MarketInfo(
    symbols = symbols.map { it.toDomain() }
)

fun ExchangeSymbolDto.toDomain(): MarketSymbol = MarketSymbol(
    symbol = symbol,
    status = status ?: "UNKNOWN",
    quoteAsset = quoteAsset ?: "",
    isSpotTradingAllowed = isSpotTradingAllowed ?: false,
    permissions = permissions ?: emptyList()
)

fun OrderBookDto.toDomain(): OrderBook = OrderBook(
    lastUpdateId = lastUpdateId,
    bids = bids.map { it.toDomain() },
    asks = asks.map { it.toDomain() }
)

fun OrderBookEntryDto.toDomain(): OrderBookEntry = OrderBookEntry(
    price = parseDoubleStrict(price, "orderBook.price"),
    quantity = parseDoubleStrict(quantity, "orderBook.quantity")
)

fun AccountInfoDto.toDomain(): Account = Account(
    makerCommission = makerCommission,
    takerCommission = takerCommission,
    buyerCommission = buyerCommission,
    sellerCommission = sellerCommission,
    canTrade = canTrade,
    canWithdraw = canWithdraw,
    canDeposit = canDeposit,
    updateTime = updateTime,
    accountType = accountType,
    balances = balances.map { it.toDomain() }
)

fun BalanceDto.toDomain(): AccountBalance = AccountBalance(
    asset = asset,
    free = parseDoubleStrict(free, "balance.free"),
    locked = parseDoubleStrict(locked, "balance.locked")
)

fun TradeResponseDto.toDomain(): OrderResponse = OrderResponse(
    symbol = symbol,
    orderId = orderId,
    clientOrderId = clientOrderId,
    transactTime = transactTime,
    price = parseDoubleStrict(price, "order.price"),
    origQty = parseDoubleStrict(origQty, "order.origQty"),
    executedQty = parseDoubleStrict(executedQty, "order.executedQty"),
    cummulativeQuoteQty = parseDoubleStrict(cummulativeQuoteQty, "order.cummulativeQuoteQty"),
    status = status,
    timeInForce = timeInForce,
    type = type,
    side = side
)

fun OrderDto.toDomain(): OrderResponse = OrderResponse(
    symbol = symbol,
    orderId = orderId,
    clientOrderId = clientOrderId,
    transactTime = transactTime ?: time ?: 0L,
    price = parseDoubleStrict(price, "order.price"),
    origQty = parseDoubleStrict(origQty, "order.origQty"),
    executedQty = parseDoubleStrict(executedQty, "order.executedQty"),
    cummulativeQuoteQty = parseDoubleStrict(cummulativeQuoteQty, "order.cummulativeQuoteQty"),
    status = status,
    timeInForce = timeInForce,
    type = type,
    side = side
)

fun MyTradeDto.toDomain(): Trade = Trade(
    tradeId = tradeId,
    price = parseDoubleStrict(price, "myTrade.price"),
    quantity = parseDoubleStrict(quantity, "myTrade.quantity"),
    timestamp = time,
    isBuyerMaker = isBuyer == isMaker
)

private fun parseDoubleStrict(value: String, field: String): Double {
    return value.toDoubleOrNull()
        ?: throw IllegalArgumentException("Invalid $field=$value")
}
