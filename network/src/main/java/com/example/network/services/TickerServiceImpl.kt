package com.example.network.services

import com.example.network.interfaces.BinancePublicApi
import com.example.network.interfaces.TickerService
import com.example.network.mapper.toDomain
import com.example.platform.model.Ticker

internal class TickerServiceImpl(
    private val api: BinancePublicApi
) : TickerService {

    override suspend fun getTickers24hr(): List<Ticker> {
        return api.getTickers24hr().map { it.toDomain() }
    }
}
