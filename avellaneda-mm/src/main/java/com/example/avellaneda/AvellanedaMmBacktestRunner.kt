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
            ?: "marketstate.csv"
        val symbolArg = args.getOrNull(1)
        val symbolsEnv = System.getenv("SYMBOLS")
        val speedup = args.getOrNull(2)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: 1.0

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
                maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull() ?: 5_000L
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
