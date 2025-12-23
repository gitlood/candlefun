package com.example.network

import com.example.network.dto.AggTradeDto
import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.Ticker24HrDto
import com.example.network.helper.NetworkConstants.BASE_URL
import com.example.network.helper.getOrThrow
import com.example.network.interfaces.BinanceMarketDataService
import com.example.network.model.MarketInfo
import com.example.network.model.MarketSymbol
import com.example.network.model.Ticker
import com.example.network.model.Trade
import io.ktor.client.HttpClient

internal class BinanceMarketDataServiceImpl(
    private val httpClient: HttpClient
) : BinanceMarketDataService {

    override suspend fun get24HrTickers(): List<Ticker> {
        val dtos = httpClient.getOrThrow<List<Ticker24HrDto>>("$BASE_URL/ticker/24hr")
        return dtos.map { dto ->
            Ticker(
                symbol = dto.symbol,
                quoteVolume = dto.quoteVolume.toDoubleOrNull() ?: 0.0,
                tradeCount = dto.count,
                lastPrice = dto.lastPrice.toDoubleOrNull() ?: 0.0
            )
        }
    }

    override suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<Trade> {
        val dtos = httpClient.getOrThrow<List<AggTradeDto>>("$BASE_URL/aggTrades") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("limit", limit.toString())
                if (fromId != null) parameters.append("fromId", fromId.toString())
            }
        }
        return dtos.map { dto ->
            Trade(
                tradeId = dto.tradeId,
                price = dto.price.toDoubleOrNull() ?: 0.0,
                quantity = dto.quantity.toDoubleOrNull() ?: 0.0,
                timestamp = dto.timestamp,
                isBuyerMaker = dto.isBuyerMaker
            )
        }
    }

    override suspend fun getExchangeInfo(): MarketInfo {
        val dto = httpClient.getOrThrow<ExchangeInfoDto>("$BASE_URL/exchangeInfo")
        return MarketInfo(
            symbols = dto.symbols.map { symbolDto ->
                MarketSymbol(
                    symbol = symbolDto.symbol,
                    status = symbolDto.status ?: "UNKNOWN",
                    quoteAsset = symbolDto.quoteAsset ?: "",
                    isSpotTradingAllowed = symbolDto.isSpotTradingAllowed ?: false,
                    permissions = symbolDto.permissions ?: emptyList()
                )
            }
        )
    }
}
