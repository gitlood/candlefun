package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.marketdata.impl.replay.MarketStateReplayer
import kotlinx.coroutines.runBlocking
import java.io.File

object AvellanedaMmBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val inputPath = args.getOrNull(0)
            ?: System.getenv("MARKETSTATE_CSV")
            ?: defaultMarketStatePath()
        val symbolArg = args.getOrNull(1)
        val symbolsEnv = System.getenv("SYMBOLS")
        val speedup = args.getOrNull(2)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: 1.0

        val inputFile = File(inputPath)
        println("Backtest input: ${inputFile.absolutePath}")
        println("Exists       : ${inputFile.exists()} sizeBytes=${if (inputFile.exists()) inputFile.length() else 0L}")
        if (inputFile.exists()) {
            val lineCount = inputFile.useLines { it.count() }
            println("LineCount    : $lineCount")
        }

        val replayer = MarketStateReplayer(File(inputPath), speedup = speedup)
        val accountRepo = SimAccountStateRepository()
        val walletConfig = InventoryWalletConfig.default()
        val inventoryRepo = CsvInventoryStateRepository(CsvWalletStore(walletConfig.walletCsvPath), walletConfig)
        val gateway = SimExecutionGateway(accountRepo, inventoryRepo)
        val symbols = resolveSymbols(inputPath, symbolArg, symbolsEnv)
        val strategies = symbols.associateWith { symbol ->
            val config = AvellanedaMmConfig.default(symbol).copy(
                orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: 0.001,
                minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull() ?: 0.0005,
                inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull() ?: 0.01,
                maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull() ?: 0.01,
                priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: 0.01,
                qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: 0.0001,
                quoteRefreshMs = System.getenv("QUOTE_REFRESH_MS")?.toLongOrNull() ?: 500L,
                maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull() ?: 5_000L,
                maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull(),
                minTopDepth = System.getenv("MIN_TOP_DEPTH")?.toDoubleOrNull(),
                maxDepthImbalance = System.getenv("MAX_DEPTH_IMBALANCE")?.toDoubleOrNull(),
                maxTradeImbalance1s = System.getenv("MAX_TRADE_IMBALANCE_1S")?.toDoubleOrNull(),
                minTradeCount1sForToxicity = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: 5,
                maxVol1s = System.getenv("MAX_VOL_1S")?.toDoubleOrNull(),
                maxVol5s = System.getenv("MAX_VOL_5S")?.toDoubleOrNull(),
                maxVol10s = System.getenv("MAX_VOL_10S")?.toDoubleOrNull(),
                logGateDecisions = System.getenv("LOG_GATES")?.toBooleanStrictOrNull() ?: false
            )
            AvellanedaMmStrategy(gateway, config)
        }

        var ticks = 0L
        replayer.stream().collect { state ->
            if (state.symbol !in strategies) return@collect
            gateway.onMarketState(state)
            strategies[state.symbol]?.onMarketState(state)
            updateMarkPrice(
                inventoryRepo,
                state.symbol,
                state.midPrice ?: state.lastTradePrice,
                state.eventTimeMs ?: state.timestampMs
            )
            ticks++
            if (ticks % 1000L == 0L) {
                println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
            }
        }

        inventoryRepo.persist()
        println("finished ticks=$ticks")
    }

    private fun defaultMarketStatePath(): String {
        val root = findProjectRoot()
        return File(root, "marketstate.csv").absolutePath
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }

    private fun resolveSymbols(inputPath: String, symbolArg: String?, symbolsEnv: String?): List<String> {
        val raw = symbolArg?.ifBlank { null } ?: symbolsEnv?.ifBlank { null }
        if (raw != null) {
            return raw.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
        }
        return loadSymbolsFromFile(File(inputPath))
    }

    private fun loadSymbolsFromFile(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        if (lines.size <= 1) return emptyList()
        return lines.drop(1)
            .mapNotNull { line -> line.split(',').firstOrNull()?.trim()?.uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun updateMarkPrice(
        inventoryRepo: CsvInventoryStateRepository,
        symbol: String,
        markPrice: Double?,
        timestampMs: Long
    ) {
        if (markPrice == null) return
        inventoryRepo.applyMarkPrice(Symbol.of(symbol), Price.fromDouble(markPrice), timestampMs)
    }
}
