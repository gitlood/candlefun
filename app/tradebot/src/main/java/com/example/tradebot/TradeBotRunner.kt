package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.historicaldata.util.OrderBookFeatureCalculator
import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.model.toCandle
import com.example.platformutil.intervalToMillis
import com.example.platformutil.resolveCandleDbPath
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.OrderBookSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.max

fun main(args: Array<String>) = runBlocking {
    TradeBotRunner.run(args)
}

object TradeBotRunner {

    private val COLORS = listOf(31, 32, 33, 34, 35, 36, 37)

    private const val DEFAULT_TRADE_LOG = "trade-events.csv"
    private const val DEFAULT_ACCOUNT_LOG = "account-snapshots.csv"

    private data class RunnerConfig(
        val liveCandles: Boolean = false,
        val tradeLogPath: String = DEFAULT_TRADE_LOG,
        val accountLogPath: String? = DEFAULT_ACCOUNT_LOG,
        val startingCapitalUsd: Double = 100_000.0
    )

    private data class BotGroup(
        val symbol: String,
        val interval: String,
        val bots: List<TradeBot>,
        val requiredBars: Int,
        val needsOrderBook: Boolean
    )

    suspend fun run(args: Array<String>) {
        val config = parseRunnerConfig(args)
        val api = BinanceTestNetApiService.create()
        val orderBookService = BinanceOrderBookService.create()
        val liveCandleService = if (config.liveCandles) BinanceApiService.create() else null
        val tradeLogger = CsvTradeLogger(config.tradeLogPath)
        val accountLogger = config.accountLogPath?.let { AccountSnapshotLogger(it) }

        val botSpecs = readBotSpecs()
        if (botSpecs.isEmpty()) {
            println(" [!] NO BOTS LOADED. Exiting.")
            return
        }

        printRosterVerbose(botSpecs)

        val bots = botSpecs.mapIndexed { idx, spec ->
            TradeBot(api, spec, tradeLogger).also { it.consoleColorCode = COLORS[idx % COLORS.size] }
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
                accountLogger?.let { logger ->
                    runCatching { api.fetchAccountInfo() }
                        .onSuccess { logger.log(it, config.startingCapitalUsd) }
                        .onFailure { println("Account snapshot failed: ${it.message}") }
                }

                for (group in groups) {
                    val key = "${group.symbol}:${group.interval}"

                    val (candles, sourceLabel) = fetchCandlesForGroup(group, config, liveCandleService)
                    if (candles.isEmpty()) {
                        println("[Cycle $tickCount][$key] No candles available ($sourceLabel).")
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

                    println("\n[Cycle $tickCount][$key] New candle ($sourceLabel) | latestOpenTime=$latestOpenTime | Candles=${candles.size}")
                    for (bot in group.bots) {
                        println("[${bot.spec.name.color(bot.consoleColorCode)}] OpenPositions=${bot.openPositions.size}")
                        bot.onCandles(candles, orderBookSnapshot)
                    }
                }
            } catch (e: Exception) {
                println("\nError in main loop: ${e.message}")
            }

            tickCount++
            delay(60_000L)
        }
    }

    private fun parseRunnerConfig(args: Array<String>): RunnerConfig {
        var liveCandles = false
        var tradeLogPath = DEFAULT_TRADE_LOG
        var accountLogPath: String? = DEFAULT_ACCOUNT_LOG
        var startingCapitalUsd = 100_000.0

        for (arg in args) {
            when {
                arg == "--live-candles" -> liveCandles = true
                arg.startsWith("--trade-log=") -> {
                    val path = arg.substringAfter("=", DEFAULT_TRADE_LOG).trim()
                    if (path.isNotEmpty()) tradeLogPath = path
                }
                arg.startsWith("--account-log=") -> {
                    val path = arg.substringAfter("=", DEFAULT_ACCOUNT_LOG).trim()
                    accountLogPath = path.ifBlank { null }
                }
                arg.startsWith("--starting-capital=") -> {
                    val value = arg.substringAfter("=").trim()
                    startingCapitalUsd = value.toDoubleOrNull() ?: startingCapitalUsd
                }
            }
        }

        return RunnerConfig(
            liveCandles = liveCandles,
            tradeLogPath = tradeLogPath,
            accountLogPath = accountLogPath,
            startingCapitalUsd = startingCapitalUsd
        )
    }

    private suspend fun fetchCandlesForGroup(
        group: BotGroup,
        config: RunnerConfig,
        liveCandleService: BinanceApiService?
    ): Pair<List<Candle>, String> {
        return if (config.liveCandles && liveCandleService != null) {
            val candles = runCatching {
                fetchLiveCandles(liveCandleService, group.symbol, group.interval, group.requiredBars)
            }.getOrElse {
                println("Live candle fetch failed for ${group.symbol}: ${it.message}")
                emptyList()
            }
            candles to "live feed"
        } else {
            val dbPath = resolveCandleDbPath(group.symbol, group.interval)
            val repo = HistoricalDataRepository.create(dbPath)
            val candles = repo.getRecentCandles(group.requiredBars)
            candles to dbPath
        }
    }

    private suspend fun fetchLiveCandles(
        service: BinanceApiService,
        symbol: String,
        interval: String,
        requiredBars: Int
    ): List<Candle> {
        val limit = requiredBars.coerceAtLeast(20).coerceAtMost(1000)
        val klines = service.getKlines(symbol = symbol, interval = interval, limit = limit)
        return klines.map { it.toCandle() }
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
        val contextBars = spec.cfg.eventStudy.contextBars
        val localLowBars =
            if (spec.cfg.profitGroup.localLowLookbackMinutes <= 0) 0
            else barsFromMinutes(spec.cfg.profitGroup.localLowLookbackMinutes, intervalMillis)
        val confluenceLookbackMinutes = if (spec.cfg.confluence.enabled) {
            if (spec.cfg.confluence.lookbackMinutes <= 0) spec.cfg.backtest.horizonMinutes
            else spec.cfg.confluence.lookbackMinutes
        } else {
            0
        }
        val confluenceBars =
            if (confluenceLookbackMinutes <= 0) 0 else barsFromMinutes(confluenceLookbackMinutes, intervalMillis)
        val minForPattern = patternBars + 2
        val minForContext = contextBars + 1
        val minForGate = lookbackBars + 1
        val minForLocalLow = localLowBars + 1
        val minForConfluence = confluenceBars + 1
        return max(
            max(max(minForPattern, minForContext), minForGate),
            max(minForLocalLow, minForConfluence)
        ).coerceAtLeast(2)
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
            if (bot.cfg.confluence.enabled) {
                val lb = if (bot.cfg.confluence.lookbackMinutes <= 0) bot.cfg.backtest.horizonMinutes else bot.cfg.confluence.lookbackMinutes
                println(
                    "Confluence  : enabled=true lb=${lb}m min=${bot.cfg.confluence.minMatches} " +
                        "buffer=${bot.cfg.confluence.breakoutBufferPct} closeAbove=${bot.cfg.confluence.breakoutRequireCloseAbove}"
                )
            }
            println("-".repeat(200))
        }
        println("$border\n")
    }

    private fun String.color(code: Int) = "\u001B[${code}m$this\u001B[0m"
}
