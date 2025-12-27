package com.example.marketdata.impl.recording

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.platform.model.UniverseConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

object MarketStateRecorderRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val outputPath = System.getenv("MARKETSTATE_RECORD_PATH")
            ?: MarketStateRecorderConfig.default().outputPath
        val outputFile = File(outputPath)
        val parentDir = outputFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            println("Output directory does not exist and could not be created: ${parentDir.absolutePath}")
            println("Set MARKETSTATE_RECORD_PATH to a writable path.")
            return@runBlocking
        }
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "SPOT").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 50
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val durationSec = System.getenv("DURATION_SEC")?.toLongOrNull()

        println("MarketState recorder starting...")
        println("Output       : $outputPath")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("DurationSec  : ${durationSec ?: 0}")

        val koinApp = startKoin {
            if (source == "FUTURES") {
                modules(networkModule, futuresModule)
            } else {
                modules(networkModule)
            }
        }
        val koin = koinApp.koin

        try {
            val symbols = resolveSymbols(symbolsEnv, topN, koin.get())
            println("Symbols (${symbols.size}): ${symbols.joinToString(", ") { it.value }}")

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val recorder = MarketStateRecorder(outputFile)
            if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                recorder.startFutures(repo, symbols, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                recorder.startSpot(repo, symbols, config)
            }

            if (durationSec != null && durationSec > 0) {
                println("Recorder running for ${durationSec}s...")
                delay(durationSec * 1_000L)
                recorder.stop()
                println("Recorder stopped after ${durationSec}s.")
            } else {
                println("Recorder running. Press Ctrl+C to stop.")
                while (true) {
                    delay(60_000L)
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun resolveSymbols(
        symbolsEnv: String?,
        topN: Int,
        universe: BinanceUniverse
    ): List<Symbol> {
        if (!symbolsEnv.isNullOrBlank()) {
            return symbolsEnv.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.asSymbol() }
        }
        println("Fetching universe...")
        val config = UniverseConfig(maxSymbols = topN)
        return universe.fetchTopSymbols(config).map { it.symbol.asSymbol() }
    }
}
