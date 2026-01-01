package com.example.network.futures.di

import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.BinancePrivateApi
import com.example.network.config.BinanceEnvs
import com.example.network.futures.BinanceFuturesTestNetApiServiceImpl
import com.example.network.futures.FuturesOrderBookServiceImpl
import com.example.network.futures.FuturesUserDataServiceImpl
import com.example.network.futures.FuturesWebSocketServiceImpl
import com.example.network.futures.config.FuturesEndpoints
import com.example.network.futures.interfaces.FuturesLiveAggTradeRepo
import com.example.network.futures.interfaces.FuturesLiveBookTickerRepo
import com.example.network.futures.interfaces.FuturesLiveDepthRepo
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.network.futures.interfaces.FuturesMarketDataService
import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.network.futures.marketdata.FuturesLiveAggTradeRepoImpl
import com.example.network.futures.marketdata.FuturesLiveBookTickerRepoImpl
import com.example.network.futures.marketdata.FuturesLiveDepthRepoImpl
import com.example.network.futures.marketdata.FuturesMarketStateRepositoryImpl
import com.example.network.futures.services.FuturesExchangeInfoServiceImpl
import com.example.network.futures.services.FuturesMarketDataServiceImpl
import com.example.network.security.BinanceSigner
import com.example.network.security.TimestampProvider
import java.io.File
import java.util.Properties
import org.koin.dsl.module

val futuresModule = module {
    val testnetApiKey = envOrLocalProperty(
        "BINANCE_FUTURES_TESTNET_API_KEY",
        "BINANCE_TESTNET_API_KEY",
        "BINANCE_TEST_KEY"
    )
    val isTestnet = testnetApiKey != null
    val customRestRaw = envOrLocalProperty("BINANCE_FUTURES_REST_BASE")
    if (!customRestRaw.isNullOrBlank()) {
        println("BINANCE_FUTURES_REST_BASE detected: $customRestRaw")
    }
    val customRest = sanitizeCustomRest(customRestRaw)
    if (!customRestRaw.isNullOrBlank() && customRest == null) {
        System.err.println("Invalid BINANCE_FUTURES_REST_BASE ignored; remove it from env or local.properties.")
    }
    val customWs = envOrLocalProperty("BINANCE_FUTURES_WS_BASE")
    val endpoints = when {
        !customRest.isNullOrBlank() -> {
            val wsValue = customWs?.takeIf { it.isNotBlank() }
                ?: FuturesEndpoints.deduced(customRest)
                ?: (if (customRest.startsWith("https://")) {
                    customRest.replaceFirst("https://", "wss://").trimEnd('/') + "/stream"
                } else {
                    customRest
                })
            FuturesEndpoints.custom(customRest, wsValue)
        }
        isTestnet -> FuturesEndpoints.usdMTest()
        else -> FuturesEndpoints.usdM()
    }
    single { endpoints }
    single<FuturesExchangeInfoService> { FuturesExchangeInfoServiceImpl(get(), BinanceEnvs.USD_M_FUTURES_TESTNET) }

    single<FuturesWebSocketService> { FuturesWebSocketServiceImpl(get(), get()) }
    single<FuturesOrderBookService> { FuturesOrderBookServiceImpl(get(), get()) }
    single<FuturesMarketDataService> { FuturesMarketDataServiceImpl(get(), get()) }

    single<FuturesLiveBookTickerRepo> { FuturesLiveBookTickerRepoImpl(get()) }
    single<FuturesLiveDepthRepo> { FuturesLiveDepthRepoImpl(get()) }
    single<FuturesLiveAggTradeRepo> { FuturesLiveAggTradeRepoImpl(get()) }

    single<FuturesMarketStateRepository> { FuturesMarketStateRepositoryImpl(get(), get(), get(), get()) }

    factory<FuturesUserDataService> {
        val mainnetKey = envOrLocalProperty("BINANCE_FUTURES_API_KEY", "BINANCE_KEY")
        val apiKey = if (isTestnet) {
            testnetApiKey
                ?: error("Testnet API key is required for futures user data stream")
        } else {
            mainnetKey
                ?: error("BINANCE_FUTURES_API_KEY (or BINANCE_KEY) is required for futures user data stream")
        }
        FuturesUserDataServiceImpl(get(), get(), apiKey)
    }

    single<BinanceFuturesTestNetApiService> {
        val apiKey = envOrLocalProperty("BINANCE_TESTNET_API_KEY", "BINANCE_TEST_KEY")
            ?: error("BINANCE_TESTNET_API_KEY (or BINANCE_TEST_KEY) is required")
        val secretKey = envOrLocalProperty("BINANCE_TESTNET_SECRET_KEY", "BINANCE_TEST_SECRET")
            ?: error("BINANCE_TESTNET_SECRET_KEY (or BINANCE_TEST_SECRET) is required")
        val signer = BinanceSigner(secretKey)
        val privateApi = BinancePrivateApi(
            client = get(),
            endpoints = BinanceEnvs.USD_M_FUTURES_TESTNET,
            signer = signer,
            apiKey = apiKey,
            timestampProvider = get<TimestampProvider>()
        )
        BinanceFuturesTestNetApiServiceImpl(privateApi)
    }
}

private fun envOrLocalProperty(vararg keys: String): String? {
    keys.forEach { key -> System.getenv(key)?.let { return it } }
    val props = loadLocalProperties() ?: return null
    keys.forEach { key -> props.getProperty(key)?.let { return it } }
    return null
}

private fun sanitizeCustomRest(value: String?): String? {
    if (value.isNullOrBlank()) return value
    val lower = value.trim().lowercase()
    if (lower.contains("demo.binance.com") || lower.contains("/en/") || lower.contains("/futures/")) {
        System.err.println("Ignoring BINANCE_FUTURES_REST_BASE (looks like a web UI URL): $value")
        return null
    }
    return value.trim()
}

private fun loadLocalProperties(): Properties? {
    var dir: File? = File(System.getProperty("user.dir"))
    repeat(6) {
        if (dir == null) return null
        val localProps = File(dir, "local.properties")
        if (localProps.exists()) {
            return Properties().apply { localProps.inputStream().use { load(it) } }
        }
        dir = dir.parentFile
    }
    return null
}
