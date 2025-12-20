package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.model.Candle
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.ExecutionMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal live/testnet trade bot:
 * - Reads candles from your DB (every minute)
 * - Detects a pattern match (from your discovered winners)
 * - Applies your SignalConfig gate (ret30m/volumeZ/contraction)
 * - Enters LONG (BUY) when signal fires
 * - Exits LONG via TP/SL/Horizon using AlgoConfig.backtest
 *
 * IMPORTANT: This is a "best-effort" example for testnet plumbing.
 * For real trading you'd add:
 * - exchange filters (minQty/stepSize), precision rounding
 * - robust price source (ticker/mark price), time sync/recvWindow
 * - order status polling + reconciliation
 */
class TradeBot(
    private val api: BinanceTestNetApiService,
    val spec: BotSpec,
) {
    private val cfg = spec.cfg
    private val patterns: Set<String> = normalizePatterns(spec.patterns.toList())

    private var lastProcessedOpenTime: Long = Long.MIN_VALUE

    private data class LongPos(
        val entryTime: Long,
        val entryPrice: Double,
    )

    private val openPositions = ArrayList<LongPos>(spec.trade.maxOpenPositions)

    suspend fun onCandles(candlesRaw: List<Candle>) {
        if (candlesRaw.isEmpty()) return
        val candles = candlesRaw.sortedBy { it.openTime }

        val latest = candles.last()
        if (latest.openTime <= lastProcessedOpenTime) return
        lastProcessedOpenTime = latest.openTime

        val intervalMillis = inferIntervalMillis(candles)
        val lookbackBars = barsFromMinutes(cfg.backtest.lookbackMinutes, intervalMillis)
        val horizonBars = barsFromMinutes(cfg.backtest.horizonMinutes, intervalMillis)
        val patternBars = cfg.eventStudy.patternBars

        if (candles.size < max(lookbackBars + 2, patternBars + 2)) return

        val lastClose = latest.close.toDoubleOrNull() ?: return
        if (!lastClose.isFinite() || lastClose <= 0.0) return

        // 1) EXIT check for all open positions
        if (openPositions.isNotEmpty()) {
            val it = openPositions.iterator()
            while (it.hasNext()) {
                val p = it.next()

                val tp = p.entryPrice * (1.0 + abs(cfg.backtest.takeProfit))
                val sl = p.entryPrice * (1.0 - abs(cfg.backtest.stopLoss))
                val hitTp = lastClose >= tp
                val hitSl = lastClose <= sl
                val hitHz =
                    (latest.openTime - p.entryTime) >= (cfg.backtest.horizonMinutes * 60_000L)

                if (hitTp || hitSl || hitHz) {
                    val reason = when {
                        hitSl -> "SL"
                        hitTp -> "TP"
                        else -> "HZ"
                    }

                    println("\n[${spec.name}] EXIT ✅ reason=$reason lastClose=$lastClose entry=${p.entryPrice}")
                    if (spec.trade.mode == ExecutionMode.TESTNET) {
                        api.createOrder(
                            symbol = spec.trade.symbol,
                            side = "SELL",
                            type = "MARKET",
                            quantity = spec.trade.quantity,
                            price = null,
                            timeInForce = null
                        )
                    }
                    it.remove()
                }
            }
        }

        // 2) ENTRY (only if we have capacity)
        if (openPositions.size >= spec.trade.maxOpenPositions) return

        val signalIndex = candles.lastIndex
        val ruleOk = passesSignalGate(candles, signalIndex, lookbackBars, intervalMillis)
        if (!ruleOk) return

        val keyPair = currentSeqKeys(candles, signalIndex, patternBars) ?: return
        val matched = patterns.contains(keyPair.seqWithLast) || patterns.contains(keyPair.seqOnly)
        if (!matched) return

        println("\n[${spec.name}] ENTRY ✅ pattern=${keyPair.seqWithLast} symbol=${spec.trade.symbol}")

        val fillPrice = if (spec.trade.mode == ExecutionMode.TESTNET) {
            val resp = api.createOrder(
                symbol = spec.trade.symbol,
                side = "BUY",
                type = "MARKET",
                quantity = spec.trade.quantity,
                price = null,
                timeInForce = null
            )
            resp.bestEffortPrice() ?: lastClose
        } else {
            // PAPER: assume filled at last close
            lastClose
        }

        openPositions.add(LongPos(entryTime = latest.openTime, entryPrice = fillPrice))
        println("[${spec.name}] ENTERED LONG ✅ price=$fillPrice qty=${spec.trade.quantity} openPositions=${openPositions.size}")
    }

// ----------------------- Pattern keying (same idea as your analyzer/backtester) -----------------------

    private data class KeyPair(val seqOnly: String, val seqWithLast: String)

    private fun currentSeqKeys(
        candles: List<Candle>,
        signalIndex: Int,
        patternBars: Int,
    ): KeyPair? {
        val entryIndex = signalIndex // use last closed candle as the signal candle
        val startPat = entryIndex - patternBars + 1
        if (startPat < 1) return null

        fun d(s: String) = s.toDoubleOrNull()

        val seq = StringBuilder()
        var lastShape = "N"

        for (i in startPat..entryIndex) {
            val o = d(candles[i].open) ?: return null
            val h = d(candles[i].high) ?: return null
            val l = d(candles[i].low) ?: return null
            val c = d(candles[i].close) ?: return null
            val range = h - l
            if (range <= 0.0) return null

            val body = abs(c - o)
            val upperW = h - max(o, c)
            val lowerW = min(o, c) - l

            val bodyFrac = body / range
            val upperFrac = upperW / range
            val lowerFrac = lowerW / range

            val dir = when {
                bodyFrac < 0.12 -> 'D'
                c > o -> 'G'
                else -> 'R'
            }
            val b = when {
                bodyFrac < 0.25 -> 0
                bodyFrac < 0.55 -> 1
                else -> 2
            }

            if (seq.isNotEmpty()) seq.append('.')
            seq.append(dir).append(b)

            if (i == entryIndex) {
                lastShape = when {
                    bodyFrac < 0.12 -> "D"
                    upperFrac > 0.55 && bodyFrac < 0.35 -> "PU"
                    lowerFrac > 0.55 && bodyFrac < 0.35 -> "PL"
                    else -> "N"
                }
            }
        }

        val seqOnly = "seq=$seq"
        val seqWithLast = "seq=$seq|last=$lastShape"
        return KeyPair(seqOnly, seqWithLast)
    }

    private fun normalizePatterns(raw: List<String>): Set<String> {
        fun normalizeToSeqKeys(line: String): List<String> {
            val t = line.trim()
            val token = Regex("""pattern=([^\s]+)""").find(t)?.groupValues?.get(1) ?: t
            val cleaned = token.trim().trimEnd(',', ';')

            val parts = cleaned.split('|')
            val seq = parts.firstOrNull { it.startsWith("seq=") } ?: return emptyList()
            val last = parts.firstOrNull { it.startsWith("last=") }

            return if (last != null) listOf(seq, "$seq|$last") else listOf(seq)
        }

        return raw.flatMap(::normalizeToSeqKeys)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toHashSet()
    }

// ----------------------- Signal gate (ret30m / volumeZ / contraction) -----------------------

    private fun passesSignalGate(
        candles: List<Candle>,
        signalIndex: Int,
        lookbackBars: Int,
        intervalMillis: Long,
    ): Boolean {
        // We compute features using the window ending at signalIndex (inclusive).
        val end = signalIndex
        val start = end - lookbackBars + 1
        if (start < 1) return false

        fun d(s: String) = s.toDoubleOrNull()

        val closes = DoubleArray(lookbackBars)
        val vols = DoubleArray(lookbackBars)

        for (k in 0 until lookbackBars) {
            val i = start + k
            closes[k] = d(candles[i].close) ?: return false
            vols[k] = d(candles[i].volume) ?: 0.0
        }

        // returns std over lookback
        val rets = DoubleArray(lookbackBars - 1)
        for (i in 1 until lookbackBars) {
            val prev = closes[i - 1]
            val cur = closes[i]
            rets[i - 1] = if (prev > 0.0) (cur / prev) - 1.0 else 0.0
        }
        val volStd = std(rets)
        val lastN = min(10, rets.size)
        val volStdRecent =
            if (lastN >= 2) std(rets.copyOfRange(rets.size - lastN, rets.size)) else 0.0
        val contraction = if (volStd == 0.0) 0.0 else volStdRecent / volStd

        val vNow = vols.last()
        val vMean = mean(vols)
        val vStd = std(vols)
        val volumeZ = if (vStd == 0.0) 0.0 else (vNow - vMean) / vStd

        val b30 = barsFromMinutes(30, intervalMillis)
        val idxA = (end - b30).coerceAtLeast(0)
        val cA = d(candles[idxA].close) ?: return false
        val cB = d(candles[end].close) ?: return false
        val ret30m = if (cA > 0.0) (cB / cA) - 1.0 else 0.0

        val s = cfg.signal
        if (ret30m > s.ret30mMax) return false
        if (volumeZ < s.volumeZMin) return false
        if (contraction < s.contractionMin) return false
        return true
    }

    private fun mean(a: DoubleArray): Double = if (a.isEmpty()) 0.0 else a.sum() / a.size
    private fun std(a: DoubleArray): Double {
        if (a.size <= 1) return 0.0
        val m = mean(a)
        var ss = 0.0
        for (x in a) ss += (x - m) * (x - m)
        val v = ss / a.size
        return kotlin.math.sqrt(max(0.0, v))
    }

// ----------------------- interval helpers -----------------------

    private fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int =
        ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

    private fun inferIntervalMillis(sortedCandles: List<Candle>): Long {
        if (sortedCandles.size < 2) return 60_000L
        val cap = min(sortedCandles.size - 1, 2048)
        val diffs = ArrayList<Long>(cap)
        for (i in 1..cap) {
            val d = sortedCandles[i].openTime - sortedCandles[i - 1].openTime
            if (d > 0) diffs.add(d)
        }
        if (diffs.isEmpty()) return 60_000L
        diffs.sort()
        return diffs[diffs.size / 2]
    }


    /**
     * Runner: pulls candles from DB once per minute and feeds into bot.
     * Replace patterns + cfg selection with your “best bot” output.
     */
    suspend fun main() {
        val api: BinanceTestNetApiService = BinanceTestNetApiService.create()
        val repo = HistoricalDataRepository.create()

        val botSpecs = readBotSpecs()
        printBotRoster(botSpecs)

        if (botSpecs.isEmpty()) {
            println("No bots loaded. Exiting.")
            return
        }

        val bots = botSpecs.map { spec ->
            TradeBot(
                api = api,
                spec = spec,
            )
        }

        var searchFrame = 0
        while (true) {
            val candles = repo.getAllCandles()

            // run each bot against the latest candle set
            for (b in bots) {
                with(b.spec) {
                    val patternsDisplay = this.patterns.joinToString(", ").let {
                        if (it.length > 30) it.take(27) + "..." else it
                    }
                    println(
                        "%-85s | %-30s | %-8.2f%% | %-8.2f%% | %-8d | %-8.2f".format(
                            b.spec.name.take(85),
                            patternsDisplay,
                            this.cfg.backtest.takeProfit * 100,
                            this.cfg.backtest.stopLoss * 100,
                            this.cfg.backtest.lookbackMinutes,
                            this.cfg.signal.volumeZMin
                        )
                    )
                }
                b.onCandles(candles)
            }

            val dots = ".".repeat((searchFrame % 3) + 1).padEnd(3)
            print("\rSearching$dots")
            System.out.flush()
            searchFrame++

            Thread.sleep(60_000L)
        }
    }

    /**
     * Best-effort price extraction; adjust to your TradeResponse shape.
     */
    private fun com.example.network.model.TradeResponse.bestEffortPrice(): Double? {
        // If your TradeResponse has a "price" field:
        val p = try {
            val priceField =
                this::class.members.firstOrNull { it.name == "price" }?.call(this) as? String
            priceField?.toDoubleOrNull()
        } catch (_: Throwable) {
            null
        }
        return p
    }
}
