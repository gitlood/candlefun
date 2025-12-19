package com.example.tradebot

import com.example.historicaldata.HistoricalDataRepository
import com.example.platformutil.AlgoConfig
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BotSpec(
    val name: String,
    val cfg: AlgoConfig,
    val patterns: Set<String>, // normalized keys: "seq=..." or "seq=...|last=X"
)

fun main() {
    var searchFrame = 0
    val repo = HistoricalDataRepository.create()

    val bots = readBotSpecs()
    printBotRoster(bots)

    var lastProcessedOpenTime: Long? = null

    while (true) {
        val dots = ".".repeat((searchFrame % 3) + 1).padEnd(3)
        print("\rSearching$dots")
        System.out.flush() // Ensure it prints immediately
        searchFrame++
        val candles = repo.getAllCandles().sortedBy { it.openTime }
        if (candles.size < 50) {
            Thread.sleep(1_000)
            continue
        }

        val intervalMillis = inferIntervalMillis(candles)
        val latest = candles.last()

        if (lastProcessedOpenTime == latest.openTime) {
            Thread.sleep(1_000)
            continue
        }
        lastProcessedOpenTime = latest.openTime

        // We act on the most recently CLOSED candle => index = lastIndex-1
        val signalIndex = candles.lastIndex - 1
        if (signalIndex <= 5) continue

        for (bot in bots) {
            val decision = detectSignal(
                candles = candles,
                signalIndex = signalIndex,
                bot = bot,
                intervalMillis = intervalMillis
            )

            if (decision != null) {
                println(
                    ">>> SIGNAL [${bot.name}] @${candles[signalIndex].openTime} " +
                            "pattern=${decision.matchedKey} entryNextOpen=${decision.entryIndex} " +
                            "tp=${pct(bot.cfg.backtest.takeProfit)} sl=${pct(bot.cfg.backtest.stopLoss)}"
                )
            }
        }

        Thread.sleep(1_000)
    }
}

data class SignalDecision(
    val matchedKey: String,
    val entryIndex: Int
)

private fun detectSignal(
    candles: List<Candle>,
    signalIndex: Int,
    bot: BotSpec,
    intervalMillis: Long
): SignalDecision? {
    val cfg = bot.cfg

    // tradable next open
    val entryIndex = signalIndex + 1
    if (entryIndex >= candles.size) return null

    val ruleLookbackBars = minutesToBars(cfg.backtest.lookbackMinutes, intervalMillis)
    val minIndexNeeded = max(
        cfg.eventStudy.patternBars + cfg.eventStudy.contextBars + 1,
        ruleLookbackBars + 2
    )
    if (signalIndex < minIndexNeeded) return null

    // 1) pattern match
    val keyPair = buildCollapsedKeys(
        candles = candles,
        entryIndex = entryIndex,
        patternBars = cfg.eventStudy.patternBars
    ) ?: return null

    val matched =
        when {
            bot.patterns.contains(keyPair.seqWithLast) -> keyPair.seqWithLast
            bot.patterns.contains(keyPair.seqOnly) -> keyPair.seqOnly
            else -> null
        } ?: return null

    // 2) rule gate (optional but recommended)
    val f = featuresAt(candles, entryIndex, ruleLookbackBars, intervalMillis) ?: return null
    val s = cfg.signal
    if (f.ret30m > s.ret30mMax) return null
    if (f.volumeZ < s.volumeZMin) return null
    if (f.contraction10vLookback < s.contractionMin) return null

    return SignalDecision(matchedKey = matched, entryIndex = entryIndex)
}

// ---------- minimal feature calc (same idea as your backtester) ----------

data class Features(
    val volumeZ: Double,
    val contraction10vLookback: Double,
    val ret30m: Double
)

private fun featuresAt(
    candles: List<Candle>,
    entryIndex: Int,
    lookbackBars: Int,
    intervalMillis: Long
): Features? {
    val end = entryIndex - 1
    val start = entryIndex - lookbackBars
    if (start !in 1..<end) return null

    val closes = DoubleArray(end - start + 1)
    val vols = DoubleArray(end - start + 1)

    var k = 0
    for (i in start..end) {
        val c = candles[i].close.toDoubleOrNull() ?: return null
        val v = candles[i].volume.toDoubleOrNull() ?: 0.0
        closes[k] = c
        vols[k] = v
        k++
    }

    // volume z on last bar of window
    val vNow = vols.last()
    val vMean = vols.average()
    val vStd = std(vols)
    val volumeZ = if (vStd == 0.0) 0.0 else (vNow - vMean) / vStd

    // contraction: std(last 10 returns) / std(all returns)
    val rets = DoubleArray(closes.size - 1)
    for (i in 1 until closes.size) {
        val prev = closes[i - 1]
        val cur = closes[i]
        rets[i - 1] = if (prev <= 0.0) 0.0 else (cur / prev) - 1.0
    }
    val volStdAll = std(rets)
    val lastN = min(10, rets.size)
    val volStdRecent =
        if (lastN >= 2) std(rets.copyOfRange(rets.size - lastN, rets.size)) else 0.0
    val contraction = if (volStdAll == 0.0) 0.0 else volStdRecent / volStdAll

    // ret30m based on interval
    val b30 = minutesToBars(30, intervalMillis)
    val aIdx = (end - b30).coerceAtLeast(0)
    val cA = candles[aIdx].close.toDoubleOrNull() ?: return null
    val cB = candles[end].close.toDoubleOrNull() ?: return null
    val ret30m = if (cA <= 0.0) 0.0 else (cB / cA) - 1.0

    return Features(volumeZ = volumeZ, contraction10vLookback = contraction, ret30m = ret30m)
}

private fun std(a: DoubleArray): Double {
    if (a.size < 2) return 0.0
    val m = a.average()
    var s = 0.0
    for (x in a) {
        val d = x - m
        s += d * d
    }
    val v = s / a.size.toDouble()
    return kotlin.math.sqrt(v)
}

// ---------- pattern key builder (collapsed) ----------

private data class KeyPair(val seqOnly: String, val seqWithLast: String)

private fun buildCollapsedKeys(
    candles: List<Candle>,
    entryIndex: Int,
    patternBars: Int
): KeyPair? {
    val startPat = entryIndex - patternBars
    if (startPat < 1 || entryIndex <= 1) return null

    fun d(s: String): Double? {
        return s.toDoubleOrNull()
    }

    val seq = StringBuilder()
    var lastShape = "N"

    for (i in startPat until entryIndex) {
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

        if (i == entryIndex - 1) {
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

private fun minutesToBars(minutes: Int, intervalMillis: Long): Int =
    ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

private fun inferIntervalMillis(sortedCandles: List<Candle>): Long {
    if (sortedCandles.size < 2) return 60_000L
    val cap = min(2048, sortedCandles.size - 1)
    val diffs = ArrayList<Long>(cap)
    for (i in 1..cap) {
        val d = sortedCandles[i].openTime - sortedCandles[i - 1].openTime
        if (d > 0) diffs.add(d)
    }
    if (diffs.isEmpty()) return 60_000L
    diffs.sort()
    return diffs[diffs.size / 2]
}

private fun pct(x: Double): String = "%.2f%%".format(x * 100.0)
