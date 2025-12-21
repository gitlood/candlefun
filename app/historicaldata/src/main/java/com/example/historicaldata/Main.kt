package com.example.historicaldata

import com.example.historicaldata.interfaces.CandleRepository
import com.example.historicaldata.util.CandleDataOrchestrator
import com.example.network.interfaces.BinanceApiService
import com.example.platformutil.CandleJob
import com.example.platformutil.DEFAULT_INTERVALS
import com.example.platformutil.DEFAULT_SYMBOLS
import com.example.platformutil.candleDbPath
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The main entry point for running the historical data update process independently.
 */
fun main(args: Array<String>) {
    if (args.contains("--mode=orderbook")) {
        val filtered = args.filterNot { it == "--mode=orderbook" }.toTypedArray()
        runOrderBookCollector(filtered)
        return
    }

    while (true) {
        try {
            runHistoricalDataUpdate(defaultCandleJobs())
        } catch (e: Exception) {
            println("An unexpected error occurred:")
            e.printStackTrace()
            // Do not rethrow in main() to allow clean exit for logging
        }
        
        println("Waiting 5 minutes for next update...")
        Thread.sleep(300_000)
    }
}

/**
 * Connects to the database, updates candle data from the API, and pushes a statistics report.
 * This is the primary public function for an external coordinator to call.
 */
fun runHistoricalDataUpdate(jobs: List<CandleJob>) {
    println("=== Historical Data Task Started ===")

    // 1. Initialize dependencies
    val binanceApiService = BinanceApiService.create()
    val candleRepository: CandleRepository = CandleRepositoryImpl()

    for (job in jobs) {
        println("Updating ${job.symbol} ${job.interval} -> ${job.dbPath}")

        // 2. Connect to the database and create tables
        Database.connect("jdbc:sqlite:${job.dbPath}", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Candles)
        }

        // 3. Update candles from the API
        val orchestrator = CandleDataOrchestrator(
            binanceApiService = binanceApiService,
            candleRepository = candleRepository,
            symbol = job.symbol,
            interval = job.interval
        )
        orchestrator.updateCandles()
        println("Candle update complete for ${job.symbol} ${job.interval}.")
    }

    println("=== Historical Data Task Finished ===")
}

private fun defaultCandleJobs(): List<CandleJob> {
    return DEFAULT_SYMBOLS.flatMap { symbol ->
        DEFAULT_INTERVALS.map { interval ->
            CandleJob(symbol, interval, candleDbPath(symbol, interval))
        }
    }
}
