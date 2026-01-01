package com.example.network.candlecollector

import com.example.platform.model.UniverseConfig
import java.io.File

data class CandleCollectorConfig(
    val sqliteJdbcUrl: String = defaultJdbcUrl(),
    val backfillDays: Int = 30 * 6,
    val maxConcurrentSymbolWorkers: Int = 5,
    val intervals: List<String> = listOf("1m"),
    val caughtUpPollMs: Long = 15_000L,
    val fastSyncDelayMs: Long = 200L,
    val logStatusEveryMs: Long = 60_000L,
    val logErrorsEveryMs: Long = 5_000L,
    val universeConfig: UniverseConfig = UniverseConfig(
        quoteAssets = setOf("USDT"),
        minQuoteVolume = 10_000_000.0,
        minTrades = 5_000,
        maxSymbols = 20
    )
) {
    companion object {
        val DEFAULT = CandleCollectorConfig()
    }
}

private fun defaultJdbcUrl(): String {
    val env = System.getenv("CANDLE_DB_JDBC") ?: System.getenv("CANDLE_DB_PATH")
    if (!env.isNullOrBlank()) {
        return if (env.startsWith("jdbc:sqlite:")) env else "jdbc:sqlite:$env"
    }

    val root = findProjectRoot()
    return "jdbc:sqlite:${File(root, "candles.db").absolutePath}"
}

private fun findProjectRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        val parent = dir.parentFile ?: return dir
        dir = parent
    }
}
