package com.example.historicaldata.util

import com.example.historicaldata.interfaces.CandleRepository
import com.example.network.interfaces.BinanceApiService
import com.example.platformutil.BINANCE_SYMBOL
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.math.min

class CandleDataOrchestrator(
    private val binanceApiService: BinanceApiService,
    private val candleRepository: CandleRepository,
) {

    fun updateCandles() = runBlocking {
        if (candleRepository.isDatabaseEmpty()) {
            println("Database is empty. Backfilling historical data...")
            backfillHistoricalData()
        } else {
            fetchLatestCandles()
        }

        val deletedCount = candleRepository.cleanupOldCandles()
        if (deletedCount > 0) {
            println("Deleted $deletedCount old candles.")
        }
    }

    private fun backfillHistoricalData() = runBlocking {
        val nineMonthsAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(270)
        val candleMs = TimeUnit.MINUTES.toMillis(5)
        val candlesPerRequest = 1000

        var cursor = nineMonthsAgo
        var totalDownloaded = 0
        val totalCandlesToFetch = (TimeUnit.DAYS.toMillis(270) / candleMs).toInt()

        println("Starting historical download (9 months)...")

        while (cursor < System.currentTimeMillis()) {
            val endTime = min(
                cursor + candlesPerRequest * candleMs - candleMs,
                System.currentTimeMillis() - candleMs
            )

            val klines = binanceApiService.getKlines(
                symbol = BINANCE_SYMBOL,
                limit = candlesPerRequest,
                startTime = cursor,
                endTime = endTime
            )

            if (klines.isEmpty()) {
                println("No more candles returned. Stopping backfill.")
                break
            }

            candleRepository.insertKlines(klines)
            totalDownloaded += klines.size

            cursor = klines.last().openTime + candleMs
            println("Downloaded $totalDownloaded of $totalCandlesToFetch historical candles")

            kotlinx.coroutines.delay(200L) // polite API delay
        }

        println("Finished historical download. Total candles downloaded: $totalDownloaded")
    }


    private fun fetchLatestCandles() = runBlocking {
        val lastOpenTime = candleRepository.getLatestCandleOpenTime()

        if (lastOpenTime != null) {
            println("Fetching latest candles since last update...")
            val klines = binanceApiService.getKlines(
                symbol = BINANCE_SYMBOL,
             //   apiKey = apiKey,
                startTime = lastOpenTime
            )

            if (klines.isNotEmpty()) {
                candleRepository.insertKlines(klines)
                println("Inserted ${klines.size} new candles.")
            } else {
                println("No new candles to fetch.")
            }
        }
    }
}
