package com.example.marketdata.impl

import java.io.File

internal data class MarketdataImplConfig(
    val jdbcUrl: String,
    val interval: String
) {
    companion object {
        fun default(): MarketdataImplConfig {
            val env = System.getenv("CANDLE_DB_JDBC") ?: System.getenv("CANDLE_DB_PATH")
            val jdbc = if (!env.isNullOrBlank()) {
                if (env.startsWith("jdbc:sqlite:")) env else "jdbc:sqlite:$env"
            } else {
                val root = findProjectRoot()
                "jdbc:sqlite:${File(root, "candles.db").absolutePath}"
            }
            val interval = System.getenv("CANDLE_DB_INTERVAL") ?: "1m"
            return MarketdataImplConfig(jdbc, interval)
        }

        private fun findProjectRoot(): File {
            var dir = File(System.getProperty("user.dir"))
            while (true) {
                if (File(dir, "settings.gradle.kts").exists()) return dir
                val parent = dir.parentFile ?: return dir
                dir = parent
            }
        }
    }
}
