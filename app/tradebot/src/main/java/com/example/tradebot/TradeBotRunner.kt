package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.model.BotSpec
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    TradeBotRunner.run()
}

object TradeBotRunner {

    suspend fun run() {
        // 1. Initialize API and Repo
        val api = BinanceTestNetApiService.create()
        val repo = HistoricalDataRepository.create()

        // 2. Load your bot specifications (from JSON, DB, or hardcoded)
        val botSpecs = readBotSpecs()

        // 3. Instantiate a Bot for every Spec
        val bots = botSpecs.map { spec -> TradeBot(api, spec) }

        // Print the roster once at startup
        printRoster(botSpecs)

        var tickCount = 0
        while (true) {
            try {
                // 4. Fetch the latest candles once per cycle
                val allCandles = repo.getAllCandles()

                // 5. CYCLE THROUGH ALL BOTS
                for (bot in bots) {
                    // Let the bot handle its own exit/entry logic
                    bot.onCandles(allCandles)
                }

                val dots = ".".repeat((tickCount % 3) + 1).padEnd(3)
                print("\r[Cycle ${tickCount++}] Scanning markets$dots")
                System.out.flush() // Ensure it prints immediately

            } catch (e: Exception) {
                println("\nError in loop: ${e.message}")
            }

            // Wait 1 minute before next cycle
            Thread.sleep(60_000L)
        }
    }

    private fun printRoster(bots: List<BotSpec>) {
        if (bots.isEmpty()) {
            println(" [!] NO BOTS LOADED ")
            return
        }

        val border = "═".repeat(160)
        println("\n$border")
        println(" ACTIVE BOT ROSTER (Count: ${bots.size})")
        println(border)
        // Table Header
        println("%-85s | %-30s | %-8s | %-8s | %-8s | %-8s".format(
            "Name", "Patterns", "TP%", "SL%", "Look(m)", "Vol-Z"
        ))
        println("-".repeat(160))

        bots.forEach { bot ->
            // Format patterns as a comma-separated string if there are multiple
            val patternsDisplay = bot.patterns.joinToString(", ").let {
                if (it.length > 30) it.take(27) + "..." else it
            }

            println("%-85s | %-30s | %-8.2f%% | %-8.2f%% | %-8d | %-8.2f".format(
                bot.name.take(85),
                patternsDisplay,
                bot.cfg.backtest.takeProfit * 100,
                bot.cfg.backtest.stopLoss * 100,
                bot.cfg.backtest.lookbackMinutes,
                bot.cfg.signal.volumeZMin
            ))
        }
        println("$border\n")
    }
}
