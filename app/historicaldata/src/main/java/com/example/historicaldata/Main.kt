package com.example.historicaldata

import com.example.historicaldata.interfaces.CandleRepository
import com.example.network.interfaces.BinanceApiService
import com.example.historicaldata.util.CandleDataOrchestrator
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The main entry point for running the historical data update process independently.
 */
fun main() {
    while (true) {
        try {
            runHistoricalDataUpdate()
        } catch (e: Exception) {
            println("An unexpected error occurred:")
            e.printStackTrace()
            // Do not rethrow in main() to allow clean exit for logging
        }
        
        println("Waiting 1 minute for next update...")
        Thread.sleep(60_000)
    }
}

/**
 * Connects to the database, updates candle data from the API, and pushes a statistics report.
 * This is the primary public function for an external coordinator to call.
 */
fun runHistoricalDataUpdate() {
    println("=== Historical Data Task Started ===")

    // 1. Initialize dependencies
    val binanceApiService = BinanceApiService.create()
    val candleRepository: CandleRepository = CandleRepositoryImpl()
    // If apiKey is missing, you might want to handle it inside CandleDataOrchestrator or pass a dummy/empty one if allowed
    val orchestrator = CandleDataOrchestrator(binanceApiService, candleRepository)

    // 2. Connect to the database and create tables
    Database.connect("jdbc:sqlite:binance.db", "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Candles)
    }

    // 3. Update candles from the API
    println("Updating candles from Binance API...")
    orchestrator.updateCandles()
    println("Candle update complete.")

    println("=== Historical Data Task Finished ===")
}
