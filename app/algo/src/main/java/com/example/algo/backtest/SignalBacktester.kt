package com.example.algo.backtest

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.ProfitGroupConfig
import com.example.platformutil.SignalConfig
import com.example.algo.model.BacktestFeatures
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SignalBacktester {

    // ---------------- Rule-gated signal backtest (uses AlgoConfig) ----------------

    private fun shouldEnter(f: BacktestFeatures, r: SignalConfig): Boolean {
        if (f.ret30m > r.ret30mMax) return false
        if (f.volumeZ < r.volumeZMin) return false
        if (f.contraction10vLookback < r.contractionMin) return false
        return true
    }

    // ---------------- Pattern backtest (config-driven, sweep-compatible) ----------------

    enum class EntryPriceMode {
        ENTRY_CANDLE_OPEN,
        ENTRY_CANDLE_LOW_DISCOVERY
    }

    enum class TimingMode {
        DISCOVERY_SAME_CANDLE,
        TRADABLE_NEXT_OPEN
    }

    /** Returned so your sweep can aggregate into one final report. */
    data class PatternBacktestRow(
        val tpPct: Double,
        val pattern: String,
        val signals: Int,
        val trades: Int,
        val winRate: Double,
        val avgNet: Double,
        val medNet: Double,
        val sumNet: Double,
        val compNet: Double,
        val tp: Int,
        val sl: Int,
        val hz: Int
    )

    fun runPatternBacktests(
        candlesRaw: List<Candle>,
        patterns: List<String>,
        cfg: AlgoConfig,
        intervalMillisOverride: Long? = null,
        timingMode: TimingMode = TimingMode.TRADABLE_NEXT_OPEN,
        entryPriceMode: EntryPriceMode = EntryPriceMode.ENTRY_CANDLE_OPEN,
        useRuleGate: Boolean = true,
    ): List<PatternBacktestRow> = runPatternBacktests(
        candlesRaw = candlesRaw,
        patterns = patterns,
        backtestConfig = cfg.backtest,
        signalConfig = cfg.signal,
        eventStudyConfig = cfg.eventStudy,
        profitGroupConfig = cfg.profitGroup,
        intervalMillisOverride = intervalMillisOverride,
        timingMode = timingMode,
        entryPriceMode = entryPriceMode,
        useRuleGate = useRuleGate
    )

    fun runPatternBacktests(
        candlesRaw: List<Candle>,
        patterns: List<String>,
        backtestConfig: BacktestConfig,
        signalConfig: SignalConfig,
        eventStudyConfig: EventStudyConfig,
        profitGroupConfig: ProfitGroupConfig,
        intervalMillisOverride: Long? = null,
        timingMode: TimingMode = TimingMode.TRADABLE_NEXT_OPEN,
        entryPriceMode: EntryPriceMode = EntryPriceMode.ENTRY_CANDLE_OPEN,
        useRuleGate: Boolean = true,
    ): List<PatternBacktestRow> {
        if (candlesRaw.isEmpty() || patterns.isEmpty()) {
            println("PatternBacktest: nothing to do (candles=${candlesRaw.size}, patterns=${patterns.size})")
            return emptyList()
        }

        val candles = candlesRaw.sortedBy { it.openTime }
        val intervalMillis = intervalMillisOverride ?: inferIntervalMillis(candles)
        val series = CandleSeries.from(candles, intervalMillis)

        val horizonBars = barsFromMinutes(backtestConfig.horizonMinutes, intervalMillis)
        val localLowLookbackBars =
            barsFromMinutesOrZero(profitGroupConfig.localLowLookbackMinutes, intervalMillis)

        // ✅ One TP per config (the bot TP), not the full threshold set
        val tpPctToTest = backtestConfig.takeProfit * 100.0

        val stopLossPct = backtestConfig.stopLoss * 100.0
        val maxDrawdownPctAllowed = profitGroupConfig.maxDrawdownAllowed * 100.0

        val requestedPatternCount = patterns.size

        val patternSet = patterns
            .flatMap(::normalizeToSeqKeys)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toHashSet()

        if (patternSet.isEmpty()) {
            println("PatternBacktest: no usable patterns after normalization (patterns=${patterns.size})")
            return emptyList()
        }

        val entryShift = if (timingMode == TimingMode.TRADABLE_NEXT_OPEN) 1 else 0
        val ruleLookbackBars = barsFromMinutes(backtestConfig.lookbackMinutes, intervalMillis)

        val minEntryIndexNeeded = max(
            max(eventStudyConfig.patternBars, eventStudyConfig.contextBars),
            if (useRuleGate) (ruleLookbackBars + 1) else 1
        ).coerceAtLeast(1)

        val minSignalIndex = (minEntryIndexNeeded - entryShift).coerceAtLeast(1)
        val maxExclusive =
            if (entryShift == 1) (series.n - horizonBars) else (series.n - horizonBars + 1)

        val indicesByPattern = HashMap<String, IntArrayList>(patternSet.size * 2)

        for (signalIndex in minSignalIndex until maxExclusive) {
            val entryIndex = signalIndex + entryShift

            val lowCheckIndex = if (entryShift == 1) signalIndex else entryIndex
            if (localLowLookbackBars > 0 && !isLocalLow(series, lowCheckIndex, localLowLookbackBars)) continue

            if (useRuleGate) {
                val f = series.featuresAt(entryIndex, ruleLookbackBars) ?: continue
                if (!shouldEnter(f, signalConfig)) continue
            }

            val keyPair = buildCollapsedKeys(
                s = series,
                entryIndex = entryIndex,
                patternBars = eventStudyConfig.patternBars,
                contextBars = eventStudyConfig.contextBars
            ) ?: continue

            val matchKey =
                when {
                    patternSet.contains(keyPair.seqWithLast) -> keyPair.seqWithLast
                    patternSet.contains(keyPair.seqOnly) -> keyPair.seqOnly
                    else -> null
                } ?: continue

            indicesByPattern.getOrPut(matchKey) { IntArrayList() }.add(entryIndex)
        }

        println("============================================================")
        println("=== Pattern Backtest (config-driven, sweep-compatible) ===")
        println("Candles: ${series.n} | Interval: ${intervalMillis / 1000}s")
        println("Timing: $timingMode | EntryPriceMode: $entryPriceMode")
        println("RuleGate: $useRuleGate (lookback=${backtestConfig.lookbackMinutes}m)")
        println("LocalLow lookback: ${profitGroupConfig.localLowLookbackMinutes}m (${localLowLookbackBars} bars)")
        println("Horizon: ${backtestConfig.horizonMinutes}m (${horizonBars} bars)")
        println(
            "TP=${fmt(tpPctToTest)}% | SL stop: ${fmt(stopLossPct)}% " +
                    (if (maxDrawdownPctAllowed > 0.0) "| MaxDD cap: ${fmt(maxDrawdownPctAllowed)}%" else "")
        )
        println("Costs: fee=${pct(backtestConfig.feePerSide)} slip=${pct(backtestConfig.slippagePerSide)} per side")
        println("Patterns requested: $requestedPatternCount | Unique normalized keys: ${patternSet.size} | Patterns with matches: ${indicesByPattern.size}")
        println("============================================================")

        return printPatternTableForTp(
            s = series,
            indicesByPattern = indicesByPattern,
            tpPct = tpPctToTest,
            horizonBars = horizonBars,
            stopLossPct = stopLossPct,
            maxDrawdownPctAllowed = maxDrawdownPctAllowed,
            feePerSide = backtestConfig.feePerSide,
            slippagePerSide = backtestConfig.slippagePerSide,
            timingMode = timingMode,
            entryPriceMode = entryPriceMode,
            allowOverlappingTrades = backtestConfig.allowOverlappingTrades,
            worstCaseIfBothHitSameCandle = backtestConfig.worstCaseIfBothHit
        )
    }

    private fun normalizeToSeqKeys(raw: String): List<String> {
        val t = raw.trim()
        val token = Regex("""pattern=([^\s]+)""").find(t)?.groupValues?.get(1) ?: t
        val cleaned = token.trim().trimEnd(',', ';')

        val parts = cleaned.split('|')
        val seq = parts.firstOrNull { it.startsWith("seq=") } ?: return emptyList()
        val last = parts.firstOrNull { it.startsWith("last=") }

        return if (last != null) listOf(seq, "$seq|$last") else listOf(seq)
    }

    // ---------------- Pattern table internals ----------------

    private data class KeyPair(val seqOnly: String, val seqWithLast: String)

    private fun printPatternTableForTp(
        s: CandleSeries,
        indicesByPattern: Map<String, IntArrayList>,
        tpPct: Double,
        horizonBars: Int,
        stopLossPct: Double,
        maxDrawdownPctAllowed: Double,
        feePerSide: Double,
        slippagePerSide: Double,
        timingMode: TimingMode,
        entryPriceMode: EntryPriceMode,
        allowOverlappingTrades: Boolean,
        worstCaseIfBothHitSameCandle: Boolean
    ): List<PatternBacktestRow> {

        val rows = ArrayList<PatternBacktestRow>(indicesByPattern.size)

        val effectiveEntryMode =
            if (timingMode == TimingMode.TRADABLE_NEXT_OPEN) EntryPriceMode.ENTRY_CANDLE_OPEN else entryPriceMode

        val effectiveStopPct =
            if (stopLossPct <= 0.0 && maxDrawdownPctAllowed > 0.0) maxDrawdownPctAllowed
            else if (stopLossPct > 0.0 && maxDrawdownPctAllowed > 0.0) min(stopLossPct, maxDrawdownPctAllowed)
            else stopLossPct

        for ((pattern, idxs) in indicesByPattern) {
            val signals = idxs.size()
            val trades = ArrayList<TradeLite>(idxs.size())

            var k = 0
            while (k < idxs.size()) {
                val entryIndex = idxs[k]

                val tr = simulateLongPercentTpSl(
                    s = s,
                    entryIndex = entryIndex,
                    horizonBars = horizonBars,
                    takeProfitPct = tpPct,
                    stopLossPct = effectiveStopPct,
                    feePerSide = feePerSide,
                    slippagePerSide = slippagePerSide,
                    entryPriceMode = effectiveEntryMode,
                    worstCaseIfBothHitSameCandle = worstCaseIfBothHitSameCandle
                )

                if (tr != null) {
                    trades.add(tr)
                    if (!allowOverlappingTrades) {
                        val exitIdx = tr.exitIndex
                        while (k < idxs.size() && idxs[k] <= exitIdx) k++
                        continue
                    }
                }
                k++
            }

            val netsSorted = trades.map { it.netPct }.sorted()
            val tpN = trades.count { it.exitKind == ExitKind.TP }
            val slN = trades.count { it.exitKind == ExitKind.SL }
            val hzN = trades.count { it.exitKind == ExitKind.HZ }
            val trCount = trades.size

            val profitable = trades.count { it.netPct > 0.0 }
            val winRate = if (trCount == 0) 0.0 else profitable.toDouble() / trCount

            val avgNet = if (netsSorted.isEmpty()) 0.0 else netsSorted.average()
            val medNet = if (netsSorted.isEmpty()) 0.0 else netsSorted[netsSorted.size / 2]
            val sumNet = netsSorted.sum()
            val compNet = compoundInTimeOrder(trades)

            rows.add(
                PatternBacktestRow(
                    tpPct = tpPct,
                    pattern = pattern,
                    signals = signals,
                    trades = trCount,
                    winRate = winRate,
                    avgNet = avgNet,
                    medNet = medNet,
                    sumNet = sumNet,
                    compNet = compNet,
                    tp = tpN,
                    sl = slN,
                    hz = hzN
                )
            )
        }

        rows.sortWith(
            compareByDescending<PatternBacktestRow> { it.compNet }
                .thenByDescending { it.sumNet }
                .thenByDescending { it.winRate }
        )

        println("============================================================")
        println("=== Pattern Backtest Table (TP=${fmt(tpPct)}%) ===")
        println("ExitKind: TP=take-profit hit | SL=stop-loss hit | HZ=horizon close (TP not hit in time)")
        println("NOTE: win% = profitable trades (net > 0), not TP-hit%.")
        println("------------------------------------------------------------")
        println("#   pattern            signals  trades     win%   avgNet    medNet    sumNet  compNet   TP   SL   HZ")
        println("----------------------------------------------------------------------------------------------------")

        rows.forEachIndexed { i, r ->
            println(
                "${(i + 1).toString().padEnd(3)} " +
                        "${r.pattern.padEnd(18)} " +
                        "${r.signals.toString().padStart(7)} " +
                        "${r.trades.toString().padStart(7)} " +
                        "${pct(r.winRate).padStart(8)} " +
                        "${pct(r.avgNet).padStart(8)} " +
                        "${pct(r.medNet).padStart(9)} " +
                        "${pct(r.sumNet).padStart(9)} " +
                        "${pct(r.compNet).padStart(8)} " +
                        "${r.tp.toString().padStart(4)} " +
                        "${r.sl.toString().padStart(4)} " +
                        "${r.hz.toString().padStart(4)}"
            )
        }
        println("============================================================")

        return rows
    }

    private enum class ExitKind { TP, SL, HZ }

    private data class TradeLite(
        val entryIndex: Int,
        val exitIndex: Int,
        val netPct: Double,
        val exitKind: ExitKind
    )

    private fun compoundInTimeOrder(trades: List<TradeLite>): Double {
        if (trades.isEmpty()) return 0.0
        val sorted = trades.sortedBy { it.entryIndex }
        var equity = 1.0
        for (t in sorted) equity *= (1.0 + t.netPct)
        return equity - 1.0
    }

    private fun isLocalLow(s: CandleSeries, entryIndex: Int, lookbackBars: Int): Boolean {
        if (lookbackBars <= 0) return true
        if (entryIndex <= 0 || entryIndex >= s.n) return false
        val lowNow = s.low[entryIndex]
        val start = (entryIndex - lookbackBars).coerceAtLeast(0)
        for (i in start until entryIndex) {
            if (s.low[i] < lowNow) return false
        }
        return true
    }

    private fun simulateLongPercentTpSl(
        s: CandleSeries,
        entryIndex: Int,
        horizonBars: Int,
        takeProfitPct: Double,
        stopLossPct: Double,
        feePerSide: Double,
        slippagePerSide: Double,
        entryPriceMode: EntryPriceMode,
        worstCaseIfBothHitSameCandle: Boolean
    ): TradeLite? {
        if (entryIndex < 0 || entryIndex >= s.n) return null
        if (horizonBars < 1) return null

        val entry = when (entryPriceMode) {
            EntryPriceMode.ENTRY_CANDLE_OPEN -> s.open[entryIndex]
            EntryPriceMode.ENTRY_CANDLE_LOW_DISCOVERY -> s.low[entryIndex]
        }
        if (!entry.isFinite() || entry <= 0.0) return null

        val tp = entry * (1.0 + abs(takeProfitPct) / 100.0)
        val sl = if (stopLossPct > 0.0) entry * (1.0 - abs(stopLossPct) / 100.0) else Double.NEGATIVE_INFINITY

        val lastIndex = entryIndex + horizonBars - 1
        if (lastIndex >= s.n) return null

        var exitKind = ExitKind.HZ
        var exitIndex = lastIndex
        var exitPrice = s.close[lastIndex]

        for (j in entryIndex..lastIndex) {
            val h = s.high[j]
            val l = s.low[j]
            if (!h.isFinite() || !l.isFinite()) continue

            val hitTP = h >= tp
            val hitSL = l <= sl

            if (hitTP && hitSL) {
                if (worstCaseIfBothHitSameCandle) {
                    exitKind = ExitKind.SL
                    exitIndex = j
                    exitPrice = sl
                } else {
                    exitKind = ExitKind.TP
                    exitIndex = j
                    exitPrice = tp
                }
                break
            } else if (hitSL) {
                exitKind = ExitKind.SL
                exitIndex = j
                exitPrice = sl
                break
            } else if (hitTP) {
                exitKind = ExitKind.TP
                exitIndex = j
                exitPrice = tp
                break
            }
        }

        if (!exitPrice.isFinite() || exitPrice <= 0.0) return null

        val entryCost = entry * (1.0 + feePerSide + slippagePerSide)
        val exitProceeds = exitPrice * (1.0 - feePerSide - slippagePerSide)
        val netPct = (exitProceeds / entryCost) - 1.0

        return TradeLite(entryIndex, exitIndex, netPct, exitKind)
    }

    private fun buildCollapsedKeys(
        s: CandleSeries,
        entryIndex: Int,
        patternBars: Int,
        contextBars: Int
    ): KeyPair? {
        val startPat = entryIndex - patternBars
        if (startPat < 1 || entryIndex <= 1) return null

        val seq = StringBuilder()
        var lastShape = "N"

        for (i in startPat until entryIndex) {
            val o = s.open[i]
            val h = s.high[i]
            val l = s.low[i]
            val c = s.close[i]
            val range = h - l
            if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) return null
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

    // ---------------- Existing trade simulator + report helpers ----------------

    private fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int =
        ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

    private fun barsFromMinutesOrZero(minutes: Int, intervalMillis: Long): Int =
        if (minutes <= 0) 0 else ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

    private fun pct(x: Double): String = String.format("%.2f%%", x * 100.0)
    private fun fmt(x: Double): String = String.format("%.6f", x)

    private class IntArrayList {
        private var a = IntArray(16)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun size(): Int = n
        operator fun get(i: Int): Int = a[i]
    }

    private fun inferIntervalMillis(sortedCandles: List<Candle>): Long {
        if (sortedCandles.size < 2) return 60_000L
        val times = sortedCandles.map { it.openTime }
        val diffs = ArrayList<Long>(min(2048, times.size - 1))
        val cap = min(times.size - 1, 2048)
        for (i in 1..cap) {
            val d = times[i] - times[i - 1]
            if (d > 0) diffs.add(d)
        }
        if (diffs.isEmpty()) return (times[1] - times[0]).takeIf { it > 0 } ?: 60_000L
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    // ---------------- CandleSeries (interval-aware ret5/15/30) ----------------

    private class CandleSeries(
        val n: Int,
        val intervalMillis: Long,
        val openTime: LongArray,
        val open: DoubleArray,
        val high: DoubleArray,
        val low: DoubleArray,
        val close: DoubleArray,
        val volume: DoubleArray
    ) {
        private val ret: DoubleArray = DoubleArray(n) { 0.0 }
        private val rangePct: DoubleArray = DoubleArray(n) { 0.0 }

        private val prefRet = DoubleArray(n + 1)
        private val prefRet2 = DoubleArray(n + 1)
        private val prefVol = DoubleArray(n + 1)
        private val prefVol2 = DoubleArray(n + 1)
        private val prefRange = DoubleArray(n + 1)
        private val prefClose = DoubleArray(n + 1)
        private val prefKClose = DoubleArray(n + 1)

        init {
            for (i in 0 until n) {
                val c = close[i]
                val h = high[i]
                val l = low[i]
                if (i > 0 && close[i - 1] > 0.0) {
                    ret[i] = (c / close[i - 1]) - 1.0
                }
                rangePct[i] = if (c > 0.0) (h - l) / c else 0.0

                prefRet[i + 1] = prefRet[i] + ret[i]
                prefRet2[i + 1] = prefRet2[i] + ret[i] * ret[i]

                val v = volume[i]
                prefVol[i + 1] = prefVol[i] + v
                prefVol2[i + 1] = prefVol2[i] + v * v

                val rp = rangePct[i]
                prefRange[i + 1] = prefRange[i] + rp

                prefClose[i + 1] = prefClose[i] + c
                prefKClose[i + 1] = prefKClose[i] + (i.toDouble() * c)
            }
        }

        fun featuresAt(i: Int, lookbackBars: Int): BacktestFeatures? {
            val end = i - 1
            val start = i - lookbackBars
            if (start < 1 || end <= start) return null

            val retStart = start + 1
            val retEnd = end
            val volStd = stdFromPrefix(prefRet, prefRet2, retStart, retEnd)

            val lastN = min(10, retEnd - retStart + 1)
            val volStdRecent = if (lastN >= 2) {
                stdFromPrefix(prefRet, prefRet2, retEnd - lastN + 1, retEnd)
            } else 0.0
            val contraction = if (volStd == 0.0) 0.0 else volStdRecent / volStd

            val rangeMean = meanFromPrefix(prefRange, start, end)

            val vNow = volume[end]
            val vMean = meanFromPrefix(prefVol, start, end)
            val vStd = stdFromPrefix(prefVol, prefVol2, start, end)
            val volumeZ = if (vStd == 0.0) 0.0 else (vNow - vMean) / vStd

            val b5 = barsFromMinutes(5, intervalMillis)
            val b15 = barsFromMinutes(15, intervalMillis)
            val b30 = barsFromMinutes(30, intervalMillis)

            val ret5 = retBetween(end - b5, end)
            val ret15 = retBetween(end - b15, end)
            val ret30 = retBetween(end - b30, end)

            val trendSlope = slopeCloseWindow(start, end)

            return BacktestFeatures(
                trendSlope = trendSlope,
                volStd = volStd,
                contraction10vLookback = contraction,
                rangeMean = rangeMean,
                volumeZ = volumeZ,
                ret5m = ret5,
                ret15m = ret15,
                ret30m = ret30
            )
        }

        private fun retBetween(aIdx: Int, bIdx: Int): Double {
            val safeA = aIdx.coerceIn(0, n - 1)
            val safeB = bIdx.coerceIn(0, n - 1)
            if (safeA >= safeB) return 0.0
            val cA = close[safeA]
            val cB = close[safeB]
            if (cA <= 0.0) return 0.0
            return (cB / cA) - 1.0
        }

        private fun meanFromPrefix(pref: DoubleArray, s: Int, e: Int): Double {
            val count = e - s + 1
            if (count <= 0) return 0.0
            return (pref[e + 1] - pref[s]) / count
        }

        private fun stdFromPrefix(pref: DoubleArray, pref2: DoubleArray, s: Int, e: Int): Double {
            val count = e - s + 1
            if (count <= 1) return 0.0
            val sum = pref[e + 1] - pref[s]
            val sum2 = pref2[e + 1] - pref2[s]
            val mean = sum / count
            val variance = (sum2 / count) - (mean * mean)
            return if (variance > 0) sqrt(variance) else 0.0
        }

        private fun slopeCloseWindow(s: Int, e: Int): Double {
            val nPts = (e - s + 1).toDouble()
            if (nPts < 2) return 0.0

            val sumX = (s + e) * nPts / 2.0
            val sumY = prefClose[e + 1] - prefClose[s]
            val sumXY = prefKClose[e + 1] - prefKClose[s]

            fun sqSum(k: Int): Double =
                if (k < 0) 0.0 else (k.toLong() * (k + 1) * (2 * k + 1) / 6.0)

            val sumX2 = sqSum(e) - sqSum(s - 1)

            val numerator = nPts * sumXY - sumX * sumY
            val denominator = nPts * sumX2 - sumX * sumX

            return if (denominator != 0.0) numerator / denominator else 0.0
        }

        companion object {
            fun from(candles: List<Candle>, intervalMillis: Long): CandleSeries {
                val n = candles.size
                val ot = LongArray(n)
                val o = DoubleArray(n)
                val h = DoubleArray(n)
                val l = DoubleArray(n)
                val c = DoubleArray(n)
                val v = DoubleArray(n)

                candles.forEachIndexed { i, candle ->
                    ot[i] = candle.openTime
                    o[i] = candle.open.toDoubleOrNull() ?: 0.0
                    h[i] = candle.high.toDoubleOrNull() ?: 0.0
                    l[i] = candle.low.toDoubleOrNull() ?: 0.0
                    c[i] = candle.close.toDoubleOrNull() ?: 0.0
                    v[i] = candle.volume.toDoubleOrNull() ?: 0.0
                }
                return CandleSeries(n, intervalMillis, ot, o, h, l, c, v)
            }
        }
    }
}
