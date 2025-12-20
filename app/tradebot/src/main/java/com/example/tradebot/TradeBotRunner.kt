package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.model.BotSpec
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    TradeBotRunner.run()
}

object TradeBotRunner {

    private val COLORS = listOf(31, 32, 33, 34, 35, 36, 37)

    suspend fun run() {
        val api = BinanceTestNetApiService.create()
        val repo = HistoricalDataRepository.create()

        val botSpecs = readBotSpecs()
        if (botSpecs.isEmpty()) {
            println(" [!] NO BOTS LOADED. Exiting.")
            return
        }

        printRosterVerbose(botSpecs)

        val bots = botSpecs.mapIndexed { idx, spec ->
            TradeBot(api, spec).also { it.consoleColorCode = COLORS[idx % COLORS.size] }
        }

        var tickCount = 0
        var lastSeenLatestOpenTime: Long = Long.MIN_VALUE

        while (true) {
            try {
                val allCandles = repo.getAllCandles()
                if (allCandles.isEmpty()) {
                    println("[Cycle $tickCount] No candles in DB.")
                    tickCount++
                    kotlinx.coroutines.delay(60_000L)
                    continue
                }

                val latest = allCandles.maxBy { it.openTime }
                val latestOpenTime = latest.openTime

                // ✅ Only run bots when a NEW candle appears
                if (latestOpenTime <= lastSeenLatestOpenTime) {
                    println("[Cycle $tickCount] No new candle yet (latestOpenTime=$latestOpenTime). Skipping.")
                    tickCount++
                    kotlinx.coroutines.delay(60_000L)
                    continue
                }

                lastSeenLatestOpenTime = latestOpenTime

                println("\n[Cycle $tickCount] New candle detected | latestOpenTime=$latestOpenTime | Candles=${allCandles.size}")

                for (bot in bots) {
                    println("[${bot.spec.name.color(bot.consoleColorCode)}] OpenPositions=${bot.openPositions.size}")
                    bot.onCandles(allCandles)
                }

                println("[Cycle $tickCount] Completed scan for all bots.")
            } catch (e: Exception) {
                println("\nError in main loop: ${e.message}")
            }

            tickCount++
            kotlinx.coroutines.delay(60_000L)
        }
    }

    private fun String.color(code: Int) = "\u001B[${code}m$this\u001B[0m"
}


    private fun printRosterVerbose(bots: List<BotSpec>) {
        val border = "═".repeat(200)
        println("\n$border")
        println(" ACTIVE BOT ROSTER (Count: ${bots.size})")
        println(border)

        bots.forEach { bot ->
            println("Name        : ${bot.name}")
            println("Symbol      : ${bot.trade.symbol} | Mode: ${bot.trade.mode} | MaxPos: ${bot.trade.maxOpenPositions}")
            println("Patterns    : ${bot.patterns.joinToString(", ")}")
            println("TP / SL     : ${bot.cfg.backtest.takeProfit*100}% / ${bot.cfg.backtest.stopLoss*100}%")
            println("Lookback    : ${bot.cfg.backtest.lookbackMinutes} min | Horizon: ${bot.cfg.backtest.horizonMinutes} min")
            println("Volume ZMin : ${bot.cfg.signal.volumeZMin}")
            println("-".repeat(200))
        }
        println("$border\n")
    }

    private fun String.color(code: Int) = "\u001B[${code}m$this\u001B[0m"
