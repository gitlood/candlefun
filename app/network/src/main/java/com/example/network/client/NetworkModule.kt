package com.example.network.client

import com.example.network.BinanceApiServiceImpl
import com.example.network.BinanceMarketDataServiceImpl
import com.example.network.BinanceOrderBookServiceImpl
import com.example.network.BinanceTestNetApiServiceImpl
import com.example.network.BinanceUniverse
import com.example.network.LiveKlineRepoImpl
import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinanceMarketDataService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.interfaces.LiveKlineRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object NetworkModule {
    val client: HttpClient by lazy {
        HttpClient(CIO) {
            expectSuccess = true
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ ->
                    // Here you can add global error handling/logging if needed
                    throw cause
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(WebSockets)
        }
    }

    fun provideBinanceApiService(httpClient: HttpClient = client): BinanceApiService {
        return BinanceApiServiceImpl(httpClient)
    }

    fun provideBinanceMarketDataService(httpClient: HttpClient = client): BinanceMarketDataService {
        return BinanceMarketDataServiceImpl(httpClient)
    }

    fun provideBinanceOrderBookService(httpClient: HttpClient = client): BinanceOrderBookService {
        return BinanceOrderBookServiceImpl(httpClient)
    }
    
    fun provideBinanceUniverse(httpClient: HttpClient = client): BinanceUniverse {
        return BinanceUniverse(httpClient)
    }
    
    fun provideLiveKlineRepo(httpClient: HttpClient = client): LiveKlineRepo {
        return LiveKlineRepoImpl(httpClient)
    }
    
    fun provideBinanceTestNetApiService(
        apiKey: String,
        secretKey: String,
        httpClient: HttpClient = client
    ): BinanceTestNetApiService {
        return BinanceTestNetApiServiceImpl(httpClient, apiKey, secretKey)
    }
}
