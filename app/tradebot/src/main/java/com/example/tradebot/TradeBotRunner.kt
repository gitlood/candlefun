package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.historicaldata.util.OrderBookFeatureCalculator
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.resolveCandleDbPath
import com.example.platformutil.intervalToMillis
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.OrderBookSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.math.max

fun main() = runBlocking {
    TradeBotRunner.run()
}

object TradeBotRunner {

    private val COLORS = listOf(31, 32, 33, 34, 35, 36, 37)

    private data class BotGroup(
        val symbol: String,
        val interval: String,
        val bots: List<TradeBot>,
        val requiredBars: Int,
        val needsOrderBook: Boolean
    )

    suspend fun run() {
        val api = BinanceTestNetApiService.create()
        val orderBookService = BinanceOrderBookService.create()

        val botSpecs = readBotSpecs()
        if (botSpecs.isEmpty()) {
            println(" [!] NO BOTS LOADED. Exiting.")
            return
        }

        printRosterVerbose(botSpecs)

        val bots = botSpecs.mapIndexed { idx, spec ->
            TradeBot(api, spec).also { it.consoleColorCode = COLORS[idx % COLORS.size] }
        }

        val groups = bots.groupBy { "${it.spec.trade.symbol}::${it.spec.trade.candleInterval}" }
            .map { (_, groupBots) ->
                val sample = groupBots.first()
                BotGroup(
                    symbol = sample.spec.trade.symbol,
                    interval = sample.spec.trade.candleInterval,
                    bots = groupBots,
                    requiredBars = groupBots.maxOf { requiredBarsFor(it.spec) },
                    needsOrderBook = groupBots.any { it.spec.cfg.orderBook.enabled }
                )
            }

        var tickCount = 0
        val lastSeenByGroup = HashMap<String, Long>()

        while (true) {
            try {
                for (group in groups) {
                    val key = "${group.symbol}:${group.interval}"
                    val dbPath = resolveCandleDbPath(group.symbol, group.interval)
                    val repo = HistoricalDataRepository.create(dbPath)
                    val candles = repo.getRecentCandles(group.requiredBars)

                    if (candles.isEmpty()) {
                        println("[Cycle $tickCount][$key] No candles in DB.")
                        continue
                    }

                    val latestOpenTime = candles.last().openTime
                    val lastSeen = lastSeenByGroup[key] ?: Long.MIN_VALUE

                    if (latestOpenTime <= lastSeen) {
                        println("[Cycle $tickCount][$key] No new candle yet (latestOpenTime=$latestOpenTime). Skipping.")
                        continue
                    }

                    lastSeenByGroup[key] = latestOpenTime

                    val orderBookSnapshot = if (group.needsOrderBook) {
                        fetchOrderBookSnapshot(orderBookService, group.symbol)
                    } else {
                        null
                    }

                    println("\n[Cycle $tickCount][$key] New candle | latestOpenTime=$latestOpenTime | Candles=${candles.size}")
                    for (bot in group.bots) {
                        println("[${bot.spec.name.color(bot.consoleColorCode)}] OpenPositions=${bot.openPositions.size}")
                        bot.onCandles(candles, orderBookSnapshot)
                    }
                }
            } catch (e: Exception) {
                println("\nError in main loop: ${e.message}")
            }

            tickCount++
            kotlinx.coroutines.delay(60_000L)
        }
    }

    private suspend fun fetchOrderBookSnapshot(
        service: BinanceOrderBookService,
        symbol: String
    ): OrderBookSnapshot? {
        return try {
            val depth = service.getDepth(symbol = symbol, limit = 100)
            OrderBookFeatureCalculator.buildSnapshot(symbol, depth, System.currentTimeMillis())
        } catch (e: Exception) {
            println("OrderBook fetch failed for $symbol: ${e.message}")
            null
        }
    }

    private fun requiredBarsFor(spec: BotSpec): Int {
        val intervalMillis = intervalToMillis(spec.trade.candleInterval)
        val lookbackBars = barsFromMinutes(spec.cfg.backtest.lookbackMinutes, intervalMillis)
        val patternBars = spec.cfg.eventStudy.patternBars
        return max(lookbackBars, patternBars).coerceAtLeast(2)
    }

    private fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int {
        val m = minutes.coerceAtLeast(1)
        val ms = intervalMillis.coerceAtLeast(1L)
        return ((m * 60_000L) / ms).toInt().coerceAtLeast(1)
    }

    private fun printRosterVerbose(bots: List<BotSpec>) {
        val border = "═".repeat(200)
        println("\n$border")
        println(" ACTIVE BOT ROSTER (Count: ${bots.size})")
        println(border)

        bots.forEach { bot ->
            println("Name        : ${bot.name}")
            println("Symbol      : ${bot.trade.symbol} | Interval: ${bot.trade.candleInterval} | Mode: ${bot.trade.mode} | MaxPos: ${bot.trade.maxOpenPositions}")
            println("Patterns    : ${bot.patterns.joinToString(", ")}")
            println("TP / SL     : ${bot.cfg.backtest.takeProfit * 100}% / ${bot.cfg.backtest.stopLoss * 100}%")
            println("Lookback    : ${bot.cfg.backtest.lookbackMinutes} min | Horizon: ${bot.cfg.backtest.horizonMinutes} min")
            println("Volume ZMin : ${bot.cfg.signal.volumeZMin}")
            println("OrderBook   : enabled=${bot.cfg.orderBook.enabled} minImb10=${bot.cfg.orderBook.minImbalance10} maxSprBps=${bot.cfg.orderBook.maxSpreadBps}")
            println("-".repeat(200))
        }
        println("$border\n")
    }

    private fun String.color(code: Int) = "\u001B[${code}m$this\u001B[0m"
}
