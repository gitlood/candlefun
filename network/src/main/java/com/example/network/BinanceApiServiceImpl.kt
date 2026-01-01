package com.example.network

import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinancePublicApi
import com.example.network.mapper.toDomain
import com.example.platform.model.Kline
import com.example.platform.model.enums.KlineInterval

internal class BinanceApiServiceImpl(
    private val api: BinancePublicApi
) : BinanceApiService {

    override suspend fun getKlines(
        symbol: String,
        interval: KlineInterval,
        limit: Int,
        startTime: Long?,
        endTime: Long?
    ): List<Kline> {
        return api.getKlines(
            symbol = symbol,
            interval = interval,
            limit = limit,
            startTime = startTime,
            endTime = endTime
        ).map { it.toDomain() }
    }
}
