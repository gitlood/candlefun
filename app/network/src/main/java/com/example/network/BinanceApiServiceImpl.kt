package com.example.network

import com.example.network.dto.KlineDto
import com.example.network.helper.NetworkConstants.BASE_URL
import com.example.network.helper.getOrThrow
import com.example.network.interfaces.BinanceApiService
import com.example.network.model.Kline
import io.ktor.client.HttpClient

internal class BinanceApiServiceImpl(
    private val client: HttpClient
) : BinanceApiService {

    override suspend fun getKlines(
        symbol: String,
        interval: String,
        limit: Int,
        startTime: Long?,
        endTime: Long?
    ): List<Kline> {
        val dtos = client.getOrThrow<List<KlineDto>>("$BASE_URL/klines") {
            url {
                parameters.append("symbol", symbol)
                parameters.append("interval", interval)
                parameters.append("limit", limit.toString())
                if (startTime != null) parameters.append("startTime", startTime.toString())
                if (endTime != null) parameters.append("endTime", endTime.toString())
            }
        }

        return dtos.map { dto ->
            Kline(
                openTime = dto.openTime,
                open = dto.open.toDouble(),
                high = dto.high.toDouble(),
                low = dto.low.toDouble(),
                close = dto.close.toDouble(),
                volume = dto.volume.toDouble(),
                closeTime = dto.closeTime,
                quoteAssetVolume = dto.quoteAssetVolume.toDouble(),
                numberOfTrades = dto.numberOfTrades,
                takerBuyBaseAssetVolume = dto.takerBuyBaseAssetVolume.toDouble(),
                takerBuyQuoteAssetVolume = dto.takerBuyQuoteAssetVolume.toDouble()
            )
        }
    }
}
