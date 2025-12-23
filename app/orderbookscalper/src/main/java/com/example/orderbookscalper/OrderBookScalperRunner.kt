package com.example.orderbookscalper

import kotlinx.coroutines.runBlocking
import kotlin.math.max

fun main(args: Array<String>) = runBlocking {
    val defaults = defaultScalperConfig()
    if (args.any { it == "--help" }) {
        printUsage(defaults)
        return@runBlocking
    }

    val config = parseConfig(args, defaults)
    logConfig(config)

    val engine = OrderBookScalperEngine(config)
    engine.start()
}

private fun parseConfig(args: Array<String>, defaults: ScalperConfig): ScalperConfig {
    fun arg(name: String): String? =
        args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")

    fun argInt(name: String, fallback: Int) = arg(name)?.toIntOrNull() ?: fallback
    fun argLong(name: String, fallback: Long) = arg(name)?.toLongOrNull() ?: fallback
    fun argDouble(name: String, fallback: Double) = arg(name)?.toDoubleOrNull() ?: fallback
    fun argBool(name: String, fallback: Boolean) = arg(name)?.toBooleanStrictOrNull() ?: fallback
    fun argExitBasis(name: String, fallback: ExitPriceBasis): ExitPriceBasis {
        val raw = arg(name)?.trim()?.lowercase() ?: return fallback
        return when (raw) {
            "mid" -> ExitPriceBasis.MID
            "last", "last_trade", "lasttrade", "trade" -> ExitPriceBasis.LAST_TRADE
            "best", "best_bid_ask", "bid_ask", "bestbidask" -> ExitPriceBasis.BEST_BID_ASK
            else -> fallback
        }
    }

    fun argSet(name: String): Set<String> = arg(name)
        ?.split(",")
        ?.map { it.trim().uppercase() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    val feePctPerSide = argDouble("fee-pct", defaults.feePctPerSide)
    val minSpreadPct = argDouble("min-spread-pct", feePctPerSide * 4.0)
    val depthLevels = argInt("depth-levels", defaults.depthLevels)
    val depthLimit = max(argInt("depth-limit", defaults.depthLimit), depthLevels)
    val csvEnabled = argBool("csv-enabled", defaults.csvEnabled)
    val csvDir = arg("csv-dir") ?: defaults.csvDir
    val cancelOnSignalFlip = argBool("cancel-on-signal-flip", defaults.cancelOnSignalFlip)
    val minLastPrice = argDouble("min-last-price", defaults.minLastPrice)
    val minFlowTrades = argInt("min-flow-trades", defaults.minFlowTrades)
    val minFlowNotional = argDouble("min-flow-notional", defaults.minFlowNotional)
    val minSignalConsecutive = argInt("min-signal-consecutive", defaults.minSignalConsecutive)


    return defaults.copy(
        quoteAsset = arg("quote-asset")?.uppercase() ?: defaults.quoteAsset,
        fixedSymbols = argSet("symbols").ifEmpty { defaults.fixedSymbols },
        excludedSymbols = argSet("exclude-symbols").ifEmpty { defaults.excludedSymbols },
        maxActiveSymbols = argInt("max-active-symbols", defaults.maxActiveSymbols),
        maxScanSymbols = argInt("max-scan-symbols", defaults.maxScanSymbols),
        minQuoteVolume = argDouble("min-quote-volume", defaults.minQuoteVolume),
        minTradeCount = argInt("min-trade-count", defaults.minTradeCount),
        depthLevels = depthLevels,
        depthLimit = depthLimit,
        selectionIntervalMs = argLong("selection-interval-ms", defaults.selectionIntervalMs),
        pollIntervalMs = argLong("poll-interval-ms", defaults.pollIntervalMs),
        tradeWindowMs = argLong("trade-window-ms", defaults.tradeWindowMs),
        entryTtlMs = argLong("entry-ttl-ms", defaults.entryTtlMs),
        entryOffsetPctOfSpread = argDouble("entry-offset-pct", defaults.entryOffsetPctOfSpread),
        maxHoldMs = argLong("max-hold-ms", defaults.maxHoldMs),
        tpMult = argDouble("tp-mult", defaults.tpMult),
        slMult = argDouble("sl-mult", defaults.slMult),
        trailArmMult = argDouble("trail-arm-mult", defaults.trailArmMult),
        trailMult = argDouble("trail-mult", defaults.trailMult),
        imbalanceThresh = argDouble("imbalance-thresh", defaults.imbalanceThresh),
        microEdgeThresh = argDouble("micro-edge-thresh", defaults.microEdgeThresh),
        flowImbalanceThresh = argDouble("flow-imbalance-thresh", defaults.flowImbalanceThresh),
        minSpreadPct = minSpreadPct,
        maxSpreadPct = argDouble("max-spread-pct", defaults.maxSpreadPct),
        feePctPerSide = feePctPerSide,
        exitSlippagePct = argDouble("exit-slippage-pct", defaults.exitSlippagePct),
        exitPriceBasis = argExitBasis("exit-price-basis", defaults.exitPriceBasis),
        startingEquity = argDouble("starting-equity", defaults.startingEquity),
        perSymbolMaxNotionalPct = argDouble(
            "per-symbol-max-notional-pct",
            defaults.perSymbolMaxNotionalPct
        ),
        maxSymbolNotional = argDouble("max-symbol-notional", defaults.maxSymbolNotional),
        maxOpenPositions = argInt("max-open-positions", defaults.maxOpenPositions),
        cooldownMs = argLong("cooldown-ms", defaults.cooldownMs),
        maxDrawdownPct = argDouble("max-drawdown-pct", defaults.maxDrawdownPct),
        minDepthNotional = argDouble("min-depth-notional", defaults.minDepthNotional),
        minVolProxyPct = argDouble("min-vol-proxy-pct", defaults.minVolProxyPct),
        volWindowMs = argLong("vol-window-ms", defaults.volWindowMs),
        maxStaleMs = argLong("max-stale-ms", defaults.maxStaleMs),
        aggTradesLimit = argInt("agg-trades-limit", defaults.aggTradesLimit),
        maxConcurrentRequests = argInt("max-concurrent-requests", defaults.maxConcurrentRequests),
        statsIntervalMs = argLong("stats-interval-ms", defaults.statsIntervalMs),
        runMs = argLong("run-ms", defaults.runMs),
        cancelOnSignalFlip = cancelOnSignalFlip,
        verbose = argBool("verbose", defaults.verbose),
        csvEnabled = csvEnabled,
        csvDir = csvDir,
        minLastPrice = minLastPrice,
        minFlowTrades = minFlowTrades,
        minFlowNotional = minFlowNotional,
        minSignalConsecutive = minSignalConsecutive,
    )
}

private fun logConfig(config: ScalperConfig) {
    println("OrderBookScalper config:")
    println("  quoteAsset=${config.quoteAsset} fixedSymbols=${config.fixedSymbols} excluded=${config.excludedSymbols}")
    println("  maxActive=${config.maxActiveSymbols} maxScan=${config.maxScanSymbols} minQuoteVol=${config.minQuoteVolume}")
    println("  minTrades=${config.minTradeCount} depthLevels=${config.depthLevels} depthLimit=${config.depthLimit}")
    println("  selectionIntervalMs=${config.selectionIntervalMs} pollIntervalMs=${config.pollIntervalMs}")
    println(
        "  tradeWindowMs=${config.tradeWindowMs} entryTtlMs=${config.entryTtlMs} " +
                "entryOffsetPct=${config.entryOffsetPctOfSpread} maxHoldMs=${config.maxHoldMs}"
    )
    println("  tpMult=${config.tpMult} slMult=${config.slMult} trailArmMult=${config.trailArmMult} trailMult=${config.trailMult}")
    println("  imbalanceThresh=${config.imbalanceThresh} microEdgeThresh=${config.microEdgeThresh} flowImbalanceThresh=${config.flowImbalanceThresh}")
    println(
        "  minSpreadPct=${config.minSpreadPct} maxSpreadPct=${config.maxSpreadPct} " +
                "feePct=${config.feePctPerSide} exitPriceBasis=${config.exitPriceBasis}"
    )
    println("  maxOpenPositions=${config.maxOpenPositions} perSymbolMaxNotionalPct=${config.perSymbolMaxNotionalPct} maxSymbolNotional=${config.maxSymbolNotional}")
    println("  startingEquity=${config.startingEquity} maxDrawdownPct=${config.maxDrawdownPct} cooldownMs=${config.cooldownMs}")
    println("  minDepthNotional=${config.minDepthNotional} minVolProxyPct=${config.minVolProxyPct}")
    println("  aggTradesLimit=${config.aggTradesLimit} maxConcurrentRequests=${config.maxConcurrentRequests}")
    println(
        "  statsIntervalMs=${config.statsIntervalMs} runMs=${config.runMs} " +
                "verbose=${config.verbose} cancelOnSignalFlip=${config.cancelOnSignalFlip}"
    )
    println("  csvEnabled=${config.csvEnabled} csvDir=${config.csvDir}")
}

private fun printUsage(defaults: ScalperConfig) {
    println("OrderBookScalper usage:")
    println("  --quote-asset=USDT")
    println("  --symbols=BTCUSDT,ETHUSDT (optional fixed list)")
    println("  --exclude-symbols=BNBUSDT")
    println("  --max-active-symbols=${defaults.maxActiveSymbols}")
    println("  --max-scan-symbols=${defaults.maxScanSymbols} (0 = unlimited)")
    println("  --min-quote-volume=${defaults.minQuoteVolume}")
    println("  --min-trade-count=${defaults.minTradeCount}")
    println("  --depth-levels=${defaults.depthLevels}")
    println("  --depth-limit=${defaults.depthLimit}")
    println("  --selection-interval-ms=${defaults.selectionIntervalMs}")
    println("  --poll-interval-ms=${defaults.pollIntervalMs}")
    println("  --trade-window-ms=${defaults.tradeWindowMs}")
    println("  --entry-ttl-ms=${defaults.entryTtlMs}")
    println("  --entry-offset-pct=${defaults.entryOffsetPctOfSpread}")
    println("  --max-hold-ms=${defaults.maxHoldMs}")
    println("  --tp-mult=${defaults.tpMult}")
    println("  --sl-mult=${defaults.slMult}")
    println("  --trail-arm-mult=${defaults.trailArmMult}")
    println("  --trail-mult=${defaults.trailMult}")
    println("  --imbalance-thresh=${defaults.imbalanceThresh}")
    println("  --micro-edge-thresh=${defaults.microEdgeThresh}")
    println("  --flow-imbalance-thresh=${defaults.flowImbalanceThresh}")
    println("  --fee-pct=${defaults.feePctPerSide}")
    println("  --min-spread-pct=${defaults.minSpreadPct}")
    println("  --max-spread-pct=${defaults.maxSpreadPct}")
    println("  --exit-slippage-pct=${defaults.exitSlippagePct}")
    println("  --exit-price-basis=${defaults.exitPriceBasis.name.lowercase()} (mid|last_trade|best_bid_ask)")
    println("  --starting-equity=${defaults.startingEquity}")
    println("  --per-symbol-max-notional-pct=${defaults.perSymbolMaxNotionalPct}")
    println("  --max-symbol-notional=${defaults.maxSymbolNotional}")
    println("  --max-open-positions=${defaults.maxOpenPositions}")
    println("  --cooldown-ms=${defaults.cooldownMs}")
    println("  --max-drawdown-pct=${defaults.maxDrawdownPct}")
    println("  --min-depth-notional=${defaults.minDepthNotional}")
    println("  --min-vol-proxy-pct=${defaults.minVolProxyPct}")
    println("  --vol-window-ms=${defaults.volWindowMs}")
    println("  --max-stale-ms=${defaults.maxStaleMs}")
    println("  --agg-trades-limit=${defaults.aggTradesLimit}")
    println("  --max-concurrent-requests=${defaults.maxConcurrentRequests}")
    println("  --stats-interval-ms=${defaults.statsIntervalMs}")
    println("  --run-ms=${defaults.runMs}")
    println("  --verbose=${defaults.verbose}")
    println("  --csv-enabled=${defaults.csvEnabled}")
    println("  --csv-dir=${defaults.csvDir}")
    println("  --cancel-on-signal-flip=${defaults.cancelOnSignalFlip}")
    println("  --smtp-host=your.smtp.net")
    println("  --smtp-port=587")
    println("  --smtp-user=username")
    println("  --smtp-pass=password")
    println("  --smtp-tls=true")
    println("  --alert-from=alerts@example.com")
    println("  --alert-to=you@example.com")
    println("  --alert-subject=Scalper Fill")
    println("  --min-last-price=${defaults.minLastPrice}")
    println("  --min-flow-trades=${defaults.minFlowTrades}")
    println("  --min-flow-notional=${defaults.minFlowNotional}")
    println("  --min-signal-consecutive=${defaults.minSignalConsecutive}")
}
