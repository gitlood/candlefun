package com.example.network.services

import com.example.network.interfaces.BinancePublicApi
import com.example.network.interfaces.ExchangeInfoService
import com.example.network.mapper.toDomain
import com.example.platform.model.MarketInfo

internal class ExchangeInfoServiceImpl(
    private val api: BinancePublicApi
) : ExchangeInfoService {

    override suspend fun getExchangeInfo(): MarketInfo {
        return api.getExchangeInfo().toDomain()
    }
}
