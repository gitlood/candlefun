package com.example.network.di

import com.example.network.BinancePrivateApi
import com.example.network.BinancePublicApiImpl
import com.example.network.BinanceTestNetApiServiceImpl
import com.example.network.BinanceUniverse
import com.example.network.BinanceWebSocketServiceImpl
import com.example.network.LiveAggTradeRepoImpl
import com.example.network.LiveBookTickerRepoImpl
import com.example.network.LiveDepthRepoImpl
import com.example.network.LiveKlineRepoImpl
import com.example.network.config.BinanceEndpoints
import com.example.network.config.BinanceEnvs
import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinancePublicApi
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.ExchangeInfoService
import com.example.network.interfaces.LiveAggTradeRepo
import com.example.network.interfaces.LiveBookTickerRepo
import com.example.network.interfaces.LiveDepthRepo
import com.example.network.interfaces.LiveKlineRepo
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.interfaces.TickerService
import com.example.network.interfaces.TradeService
import com.example.network.marketstate.MarketStateRepositoryImpl
import com.example.network.security.BinanceSigner
import com.example.network.security.SystemTimestampProvider
import com.example.network.security.TimestampProvider
import com.example.network.services.ExchangeInfoServiceImpl
import com.example.network.services.TickerServiceImpl
import com.example.network.services.TradeServiceImpl
import com.example.network.universe.UniverseFetcher
import com.example.network.universe.UniverseFilter
import com.example.network.universe.UniverseRanker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val networkModule = module {
    // --- Singletons --- //
    single<HttpClient> {
        HttpClient(CIO) {
            expectSuccess = true
            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ -> throw cause }
            }
            install(HttpTimeout) {
                val timeoutMs = System.getenv("NETWORK_REQUEST_TIMEOUT_MS")?.toLongOrNull() ?: 120000L
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(WebSockets)
        }
    }

    single<TimestampProvider> { SystemTimestampProvider() }

    // FIX: Add definition for BinanceEndpoints
    single<BinanceEndpoints> { BinanceEnvs.SPOT }

    // --- Public API --- //
    single<BinancePublicApi> { BinancePublicApiImpl(get(), get()) }

    // --- Granular Services (public) --- //
    single<BinanceApiService> { com.example.network.BinanceApiServiceImpl(get()) }
    single<ExchangeInfoService> { ExchangeInfoServiceImpl(get()) }
    single<TickerService> { TickerServiceImpl(get()) }
    single<TradeService> { TradeServiceImpl(get()) }
    single<BinanceOrderBookService> { com.example.network.BinanceOrderBookServiceImpl(get()) }

    // --- WebSocket --- //
    single<BinanceWebSocketService> { BinanceWebSocketServiceImpl(get(), get()) }
    single<LiveKlineRepo> { LiveKlineRepoImpl(get()) }
    single<LiveBookTickerRepo> { LiveBookTickerRepoImpl(get()) }
    single<LiveDepthRepo> { LiveDepthRepoImpl(get()) }
    single<LiveAggTradeRepo> { LiveAggTradeRepoImpl(get()) }
    single<MarketStateRepository> { MarketStateRepositoryImpl(get(), get(), get(), get()) }

    // --- Universe --- //
    single { UniverseFetcher(get(), get()) }
    single { UniverseFilter() }
    single { UniverseRanker() }
    single { BinanceUniverse(get(), get(), get()) }

    // --- Private/Signed API (factory for different credentials) --- //
    factory<BinanceTestNetApiService> { (apiKey: String, secretKey: String) ->
        val signer = BinanceSigner(secretKey)
        val privateApi = BinancePrivateApi(
            client = get(),
            endpoints = BinanceEnvs.TESTNET, // Explicitly use TESTNET env
            signer = signer,
            apiKey = apiKey,
            timestampProvider = get()
        )
        BinanceTestNetApiServiceImpl(privateApi)
    }

}
