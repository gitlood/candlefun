package com.example.algo.backtest

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.ProfitGroupConfig
import com.example.platformutil.OrderBookSignalConfig
import com.example.platformutil.SignalConfig
import com.example.platformutil.PatternBuckets
import com.example.algo.model.BacktestFeatures
import com.example.platformutil.model.Candle
import com.example.platformutil.model.OrderBookSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SignalBacktester {

    // ---------------- Rule-gated signal backtest ----------------

    private fun shouldEnter(
        f: BacktestFeatures,
        r: SignalConfig,
        orderBook: OrderBookSignalConfig
    ): Boolean {
        // Don’t long deep dumps
        if (f.ret30m < r.ret30mMin) return false

        // Allow below-average volume by default (negative z)
        if (f.volumeZ < r.volumeZMin) return false

        // contraction = recentVol / baselineVol; require NOT too expanded
        if (f.contraction10vLookback > r.contractionMax) return false

        // Optional trend filter
        if (f.trendSlope < r.trendSlopeMin) return false

        if (orderBook.enabled) {
            if (!f.orderBookAvailable) return false
            val spreadBps = f.orderBookSpreadBps ?: Double.POSITIVE_INFINITY
            val imbalance = f.orderBookImbalance10 ?: Double.NEGATIVE_INFINITY
            if (spreadBps > orderBook.maxSpreadBps) return false
            if (imbalance < orderBook.minImbalance10) return false
        }

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
        val tradesPerDay: Double,
        val winRate: Double,
        val avgNet: Double,
        val medNet: Double,
        val sumNet: Double,
        val compNet: Double,
        val tp: Int,
        val sl: Int,
        val hz: Int
    )

    // --- Improvement bundle:
    // (1) Full-key matching (seq|ret|rng|vol|last) instead of collapsing to seq-only
    // (2) Preference to most-specific match when preferSeqOnly=false
    // (3) Breakout confirmation entry (setup -> breakout -> enter)
    // (4) Optional ignoreCosts mode (sanity)
    // (5) Candle time fixed: use openTime everywhere (already done here)

    fun runPatternBacktests(
        candlesRaw: List<Candle>,
        patterns: List<String>,
        cfg: AlgoConfig,
        intervalMillisOverride: Long? = null,
        timingMode: TimingMode = TimingMode.TRADABLE_NEXT_OPEN,
        entryPriceMode: EntryPriceMode = EntryPriceMode.ENTRY_CANDLE_OPEN,
        useRuleGate: Boolean = true,
        orderBookSnapshots: List<OrderBookSnapshot>? = null,
        printReport: Boolean = true,
        /**
         * IMPORTANT:
         * true  = prefer broad matches (seq-only first)
         * false = prefer specific matches (FULL KEY first: seq|ret|rng|vol|last, then seq|last, then seq)
         */
        preferSeqOnly: Boolean = false,

        // ---- Breakout confirmation (recommended for "pre-breakout setups") ----
        useBreakoutConfirm: Boolean = true,
        breakoutLookaheadMinutes: Int = cfg.backtest.horizonMinutes, // default: allow breakout within horizon
        breakoutBufferPct: Double = 0.0005, // 0.05% above setup-high (tune)
        breakoutRequireCloseAbove: Boolean = false, // if true: require close >= threshold (stricter)

        // ---- Sanity: turn off costs to see if signal has ANY raw edge ----
        ignoreCosts: Boolean = false,
    ): List<PatternBacktestRow> = runPatternBacktests(
        candlesRaw = candlesRaw,
        patterns = patterns,
        backtestConfig = cfg.backtest,
        signalConfig = cfg.signal,
        orderBookConfig = cfg.orderBook,
        eventStudyConfig = cfg.eventStudy,
        profitGroupConfig = cfg.profitGroup,
        intervalMillisOverride = intervalMillisOverride,
        timingMode = timingMode,
        entryPriceMode = entryPriceMode,
        useRuleGate = useRuleGate,
        orderBookSnapshots = orderBookSnapshots,
        printReport = printReport,
        preferSeqOnly = preferSeqOnly,
        useBreakoutConfirm = useBreakoutConfirm,
        breakoutLookaheadMinutes = breakoutLookaheadMinutes,
        breakoutBufferPct = breakoutBufferPct,
        breakoutRequireCloseAbove = breakoutRequireCloseAbove,
        ignoreCosts = ignoreCosts
    )

    private data class PatternQuery(
        val normalizedKey: String,  // e.g. "seq=R2.R2.R1|ret=DN2|rng=L|vol=b|last=N"
        val seqKey: String,         // e.g. "seq=R2.R2.R1"
        val last: String?,          // e.g. "N"
        val ret: String?,           // e.g. "DN2"
        val rng: String?,           // e.g. "L"
        val vol: String?,           // e.g. "b"
        val specificity: Int        // # of optional tokens present
    )

    private fun parsePatternQuery(raw: String): PatternQuery? {
        val t0 = raw.trim()
        if (t0.isBlank()) return null

        val token0 = Regex("""pattern=([^\s]+)""")
            .find(t0)?.groupValues?.get(1)
            ?: t0

        val cleaned = token0
            .trim().trimEnd(',', ';')
            .replace("seq_", "seq=")
            .replace("last_", "last=")

        val parts = cleaned.split('|').map { it.trim() }.filter { it.isNotBlank() }

        val seqToken = parts.firstOrNull { it.startsWith("seq=") } ?: return null

        val retToken = parts.firstOrNull { it.startsWith("ret=") }
        val rngToken = parts.firstOrNull { it.startsWith("rng=") }
        val volToken = parts.firstOrNull { it.startsWith("vol=") }
        val lastToken = parts.firstOrNull { it.startsWith("last=") }

        val last = lastToken?.substringAfter("last=", "")
        val ret = retToken?.substringAfter("ret=", "")
        val rng = rngToken?.substringAfter("rng=", "")
        val vol = volToken?.substringAfter("vol=", "")

        val normalized = buildList {
            add(seqToken)
            if (!ret.isNullOrBlank()) add("ret=$ret")
            if (!rng.isNullOrBlank()) add("rng=$rng")
            if (!vol.isNullOrBlank()) add("vol=$vol")
            if (!last.isNullOrBlank()) add("last=$last")
        }.joinToString("|")

        val specificity =
            (if (!last.isNullOrBlank()) 1 else 0) +
                    (if (!ret.isNullOrBlank()) 1 else 0) +
                    (if (!rng.isNullOrBlank()) 1 else 0) +
                    (if (!vol.isNullOrBlank()) 1 else 0)

        return PatternQuery(
            normalizedKey = normalized,
            seqKey = seqToken,
            last = last,
            ret = ret,
            rng = rng,
            vol = vol,
            specificity = specificity
        )
    }

    private data class KeyBundle(
        val seqKey: String,
        val last: String,
        val ret: String,
        val rng: String,
        val vol: String,
        val setupHigh: Double
    ) {
        val seqOnly: String get() = seqKey
        val seqWithLast: String get() = "$seqKey|last=$last"
        val fullKey: String get() = "$seqKey|ret=$ret|rng=$rng|vol=$vol|last=$last"
    }

    private fun matchesStrict(q: PatternQuery, k: KeyBundle): Boolean {
        if (q.last != null && q.last != k.last) return false
        if (q.ret != null && q.ret != k.ret) return false
        if (q.rng != null && q.rng != k.rng) return false
        if (q.vol != null && q.vol != k.vol) return false
        return true
    }

    private fun matchesRelaxed(q: PatternQuery, k: KeyBundle): Boolean {
        // Relaxed means: seq must match (already indexed), and if last is specified it must match.
        // ret/rng/vol are ignored in relaxed mode.
        if (q.last != null && q.last != k.last) return false
        return true
    }

    private fun pickBestMatch(
        candidates: List<PatternQuery>,
        k: KeyBundle,
        preferSeqOnly: Boolean
    ): PatternQuery? {
        if (candidates.isEmpty()) return null

        val strictMatches = candidates.filter { matchesStrict(it, k) }
        if (strictMatches.isNotEmpty()) {
            return if (preferSeqOnly) {
                // Least-specific first: seq-only beats seq|last beats full key
                strictMatches.minWithOrNull(
                    compareBy<PatternQuery> { it.specificity }.thenBy { it.normalizedKey.length }
                )
            } else {
                // Most-specific first: full key beats seq|last beats seq-only
                strictMatches.maxWithOrNull(
                    compareBy<PatternQuery> { it.specificity }.thenBy { it.normalizedKey.length }
                )
            }
        }

        if (!preferSeqOnly) return null

        val relaxedMatches = candidates.filter { matchesRelaxed(it, k) }
        if (relaxedMatches.isEmpty()) return null

        return relaxedMatches.minWithOrNull(
            compareBy<PatternQuery> { it.specificity }.thenBy { it.normalizedKey.length }
        )
    }

    fun runPatternBacktests(
        candlesRaw: List<Candle>,
        patterns: List<String>,
        backtestConfig: BacktestConfig,
        signalConfig: SignalConfig,
        orderBookConfig: OrderBookSignalConfig,
        eventStudyConfig: EventStudyConfig,
        profitGroupConfig: ProfitGroupConfig,
        intervalMillisOverride: Long? = null,
        timingMode: TimingMode = TimingMode.TRADABLE_NEXT_OPEN,
        entryPriceMode: EntryPriceMode = EntryPriceMode.ENTRY_CANDLE_OPEN,
        useRuleGate: Boolean = true,
        orderBookSnapshots: List<OrderBookSnapshot>? = null,
        printReport: Boolean = true,
        preferSeqOnly: Boolean = false,

        useBreakoutConfirm: Boolean = true,
        breakoutLookaheadMinutes: Int = backtestConfig.horizonMinutes,
        breakoutBufferPct: Double = 0.0005,
        breakoutRequireCloseAbove: Boolean = false,

        ignoreCosts: Boolean = false
    ): List<PatternBacktestRow> {
        if (candlesRaw.isEmpty() || patterns.isEmpty()) {
            if (printReport) {
                println("PatternBacktest: nothing to do (candles=${candlesRaw.size}, patterns=${patterns.size})")
            }
            return emptyList()
        }

        val candles = candlesRaw.sortedBy { it.openTime }
        val intervalMillis = intervalMillisOverride ?: inferIntervalMillis(candles)
        val orderBookSeries = buildOrderBookSeries(candles, orderBookSnapshots)
        val series = CandleSeries.from(candles, intervalMillis, orderBookSeries)

        val horizonBars = barsFromMinutes(backtestConfig.horizonMinutes, intervalMillis)
        val localLowLookbackBars =
            barsFromMinutesOrZero(profitGroupConfig.localLowLookbackMinutes, intervalMillis)

        val tpPctToTest = backtestConfig.takeProfit * 100.0
        val stopLossPct = backtestConfig.stopLoss * 100.0
        val maxDrawdownPctAllowed = profitGroupConfig.maxDrawdownAllowed * 100.0

        val requestedPatternCount = patterns.size

        // Parse + index patterns by seq=...
        val uniqueKeys = HashSet<String>(patterns.size * 2)
        val queriesBySeq = HashMap<String, MutableList<PatternQuery>>(patterns.size * 2)

        for (p in patterns) {
            val q = parsePatternQuery(p) ?: continue
            uniqueKeys.add(q.normalizedKey)
            queriesBySeq.getOrPut(q.seqKey) { mutableListOf() }.add(q)
        }

        if (queriesBySeq.isEmpty()) {
            if (printReport) {
                println("PatternBacktest: no usable patterns after normalization (patterns=${patterns.size})")
            }
            return emptyList()
        }

        // EntryShift used ONLY for building the setup key (pattern ends at entryIndex-1 when shift=1).
        val entryShiftForKey = if (timingMode == TimingMode.TRADABLE_NEXT_OPEN) 1 else 0

        val ruleLookbackBars = barsFromMinutes(backtestConfig.lookbackMinutes, intervalMillis)
        val breakoutLookaheadBars = barsFromMinutes(breakoutLookaheadMinutes, intervalMillis)

        val minEntryIndexNeeded = max(
            max(eventStudyConfig.patternBars, eventStudyConfig.contextBars),
            if (useRuleGate) (ruleLookbackBars + 1) else 1
        ).coerceAtLeast(1)

        val minSignalIndex = (minEntryIndexNeeded - entryShiftForKey).coerceAtLeast(1)
        val maxSignalIndexExclusive = (series.n - 1).coerceAtLeast(minSignalIndex)

        val indicesByPattern = HashMap<String, IntArrayList>(uniqueKeys.size * 2)

        for (signalIndex in minSignalIndex until maxSignalIndexExclusive) {
            val keyEvalIndex = signalIndex + entryShiftForKey

            // local low check is about the last candle of the pattern (signalIndex when shift=1)
            val lowCheckIndex = if (entryShiftForKey == 1) signalIndex else keyEvalIndex
            if (localLowLookbackBars > 0 && !isLocalLow(series, lowCheckIndex, localLowLookbackBars)) continue

            val key = buildKeyBundle(
                s = series,
                entryIndex = keyEvalIndex,
                patternBars = eventStudyConfig.patternBars,
                contextBars = eventStudyConfig.contextBars
            ) ?: continue

            val seqCandidates = queriesBySeq[key.seqKey] ?: continue
            val picked = pickBestMatch(seqCandidates, key, preferSeqOnly) ?: continue
            val matchKey = picked.normalizedKey

            val entryIndex: Int = if (useBreakoutConfirm) {
                if (!key.setupHigh.isFinite() || key.setupHigh <= 0.0) continue

                val threshold = key.setupHigh * (1.0 + breakoutBufferPct)
                val trigger = findBreakoutTriggerIndex(
                    s = series,
                    startIndex = keyEvalIndex,
                    maxLookaheadBars = breakoutLookaheadBars,
                    threshold = threshold,
                    requireCloseAbove = breakoutRequireCloseAbove
                ) ?: continue

                // tradable entry = next open after trigger candle if TRADABLE_NEXT_OPEN
                val proposed = if (timingMode == TimingMode.TRADABLE_NEXT_OPEN) trigger + 1 else trigger
                if (proposed < 0 || proposed >= series.n) continue

                // Need enough space to simulate horizon from entry
                val last = proposed + horizonBars - 1
                if (last >= series.n) continue

                proposed
            } else {
                // Old behavior: enter immediately at keyEvalIndex
                val proposed = keyEvalIndex
                val last = proposed + horizonBars - 1
                if (last >= series.n) continue
                proposed
            }

            if (useRuleGate) {
                val f = series.featuresAt(entryIndex, ruleLookbackBars) ?: continue
                if (!shouldEnter(f, signalConfig, orderBookConfig)) continue
            }

            indicesByPattern.getOrPut(matchKey) { IntArrayList() }.add(entryIndex)
        }

        if (printReport) {
            println("============================================================")
            println("=== Pattern Backtest (config-driven, sweep-compatible) ===")
            println("Candles: ${series.n} | Interval: ${intervalMillis / 1000}s")
            println("Timing: $timingMode | EntryPriceMode: $entryPriceMode | preferSeqOnly=$preferSeqOnly")
            println("RuleGate: $useRuleGate (lookback=${backtestConfig.lookbackMinutes}m)")
            if (useRuleGate) {
                println(
                    "Gate: ret30m>=${pct(signalConfig.ret30mMin)}  " +
                            "volZ>=${fmt(signalConfig.volumeZMin)}  " +
                            "contr<=${fmt(signalConfig.contractionMax)}  " +
                            "slope>=${fmt(signalConfig.trendSlopeMin)}"
                )
            }
            println("LocalLow lookback: ${profitGroupConfig.localLowLookbackMinutes}m (${localLowLookbackBars} bars)")
            println("Horizon: ${backtestConfig.horizonMinutes}m (${horizonBars} bars)")
            println(
                "TP=${fmt(tpPctToTest)}% | SL stop: ${fmt(stopLossPct)}% " +
                        (if (maxDrawdownPctAllowed > 0.0) "| MaxDD cap: ${fmt(maxDrawdownPctAllowed)}%" else "")
            )
            println(
                "BreakoutConfirm: $useBreakoutConfirm " +
                        "(lookahead=${breakoutLookaheadMinutes}m/${breakoutLookaheadBars} bars, " +
                        "buffer=${pct(breakoutBufferPct)}, closeAbove=$breakoutRequireCloseAbove)"
            )
            val feeUsed = if (ignoreCosts) 0.0 else backtestConfig.feePerSide
            val slipUsed = if (ignoreCosts) 0.0 else backtestConfig.slippagePerSide
            println("Costs: fee=${pct(feeUsed)} slip=${pct(slipUsed)} per side (ignoreCosts=$ignoreCosts)")
            println(
                "Patterns requested: $requestedPatternCount | Unique normalized keys: ${uniqueKeys.size} | " +
                        "Seq buckets: ${queriesBySeq.size} | Patterns with matches: ${indicesByPattern.size}"
            )
            println("============================================================")
        }

        return printPatternTableForTp(
            s = series,
            indicesByPattern = indicesByPattern,
            tpPct = tpPctToTest,
            horizonBars = horizonBars,
            stopLossPct = stopLossPct,
            maxDrawdownPctAllowed = maxDrawdownPctAllowed,
            feePerSide = if (ignoreCosts) 0.0 else backtestConfig.feePerSide,
            slippagePerSide = if (ignoreCosts) 0.0 else backtestConfig.slippagePerSide,
            timingMode = timingMode,
            entryPriceMode = entryPriceMode,
            allowOverlappingTrades = backtestConfig.allowOverlappingTrades,
            worstCaseIfBothHitSameCandle = backtestConfig.worstCaseIfBothHit,
            printReport = printReport
        )
    }

    // ---------------- Pattern table internals ----------------

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

    private fun findBreakoutTriggerIndex(
        s: CandleSeries,
        startIndex: Int,
        maxLookaheadBars: Int,
        threshold: Double,
        requireCloseAbove: Boolean
    ): Int? {
        val endExclusive = min(s.n, startIndex + maxLookaheadBars + 1)
        if (startIndex < 0 || startIndex >= s.n) return null
        if (!threshold.isFinite() || threshold <= 0.0) return null

        for (j in startIndex until endExclusive) {
            val h = s.high[j]
            if (!h.isFinite()) continue
            if (h >= threshold) {
                if (!requireCloseAbove) return j
                val c = s.close[j]
                if (c.isFinite() && c >= threshold) return j
            }
        }
        return null
    }

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
        worstCaseIfBothHitSameCandle: Boolean,
        printReport: Boolean
    ): List<PatternBacktestRow> {

        val rows = ArrayList<PatternBacktestRow>(indicesByPattern.size)

        val effectiveEntryMode =
            if (timingMode == TimingMode.TRADABLE_NEXT_OPEN) EntryPriceMode.ENTRY_CANDLE_OPEN else entryPriceMode

        val effectiveStopPct =
            if (stopLossPct <= 0.0 && maxDrawdownPctAllowed > 0.0) maxDrawdownPctAllowed
            else if (stopLossPct > 0.0 && maxDrawdownPctAllowed > 0.0) min(stopLossPct, maxDrawdownPctAllowed)
            else stopLossPct

        val spanDays = ((s.openTime[s.n - 1] - s.openTime[0]).toDouble() / 86_400_000.0).let {
            if (it.isFinite() && it > 0.0) it else 1.0
        }

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

            val tradesPerDay = trCount / spanDays

            rows.add(
                PatternBacktestRow(
                    tpPct = tpPct,
                    pattern = pattern,
                    signals = signals,
                    trades = trCount,
                    tradesPerDay = tradesPerDay,
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
                .thenByDescending { it.tradesPerDay }
                .thenByDescending { it.sumNet }
                .thenByDescending { it.winRate }
        )

        if (printReport) {
            println("============================================================")
            println("=== Pattern Backtest Table (TP=${fmt(tpPct)}%) ===")
            println("ExitKind: TP=take-profit hit | SL=stop-loss hit | HZ=horizon close (TP not hit in time)")
            println("NOTE: win% = profitable trades (net > 0), not TP-hit%.")
            println("------------------------------------------------------------")
            println("#   pattern                               signals  trades  tr/day    win%   avgNet    medNet    sumNet  compNet   TP   SL   HZ")
            println("-------------------------------------------------------------------------------------------------------------------------------")

            rows.forEachIndexed { i, r ->
                println(
                    "${(i + 1).toString().padEnd(3)} " +
                            "${r.pattern.padEnd(36)} " +
                            "${r.signals.toString().padStart(7)} " +
                            "${r.trades.toString().padStart(7)} " +
                            "${fmt(r.tradesPerDay).padStart(7)} " +
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
        }

        return rows
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

    private fun buildKeyBundle(
        s: CandleSeries,
        entryIndex: Int,
        patternBars: Int,
        contextBars: Int
    ): KeyBundle? {
        val startPat = entryIndex - patternBars
        val startCtx = (entryIndex - contextBars).coerceAtLeast(0)
        if (startPat < 1 || entryIndex <= 1) return null

        val seq = StringBuilder()
        var lastShape = "N"
        var setupHigh = Double.NEGATIVE_INFINITY
        var patRangeSum = 0.0
        var patVolSum = 0.0

        var rangeSum = 0.0
        var rangeN = 0
        var volSum = 0.0
        var volN = 0

        for (i in startCtx until entryIndex) {
            val h = s.high[i]
            val l = s.low[i]
            val v = s.volume[i]
            if (!h.isFinite() || !l.isFinite()) continue
            val range = h - l
            if (range > 0.0) {
                rangeSum += range
                rangeN++
            }
            if (v.isFinite()) {
                volSum += v
                volN++
            }
        }
        val meanRange = if (rangeN == 0) 0.0 else rangeSum / rangeN
        val meanVol = if (volN == 0) 0.0 else volSum / volN

        for (i in startPat until entryIndex) {
            val o = s.open[i]
            val h = s.high[i]
            val l = s.low[i]
            val c = s.close[i]
            val range = h - l
            if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) return null
            if (range <= 0.0) return null

            setupHigh = max(setupHigh, h)
            patRangeSum += range
            val v = s.volume[i]
            if (v.isFinite()) patVolSum += v

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

        val patRangeMean = patRangeSum / patternBars.toDouble()
        val patVolMean = patVolSum / patternBars.toDouble()

        val rngBucket = bucketRange(patRangeMean, meanRange)

        val volRel = if (meanVol <= 0.0) Double.NaN else patVolMean / meanVol
        val volBucket = bucketVol(volRel)

        val end = entryIndex - 1
        val c0 = s.close[startPat]
        val c1 = s.close[end]
        if (!c0.isFinite() || !c1.isFinite() || c0 <= 0.0) return null
        val ret = (c1 / c0) - 1.0
        val retBucket = bucketRet(ret)

        val seqKey = "seq=$seq"
        return KeyBundle(
            seqKey = seqKey,
            last = lastShape,
            ret = retBucket,
            rng = rngBucket,
            vol = volBucket,
            setupHigh = setupHigh
        )
    }

    private fun bucketRet(ret: Double): String {
        return PatternBuckets.bucketRet(ret)
    }

    private fun bucketRange(rangeNow: Double, rangeMean: Double): String {
        if (!rangeNow.isFinite() || !rangeMean.isFinite() || rangeMean <= 0.0) return "N"
        return PatternBuckets.bucketRangeRatio(rangeNow / rangeMean)
    }

    private fun bucketVol(volumeRatio: Double): String {
        return PatternBuckets.bucketVolumeRatio(volumeRatio)
    }

    // ---------------- Helpers ----------------

    private fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int =
        ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

    private fun barsFromMinutesOrZero(minutes: Int, intervalMillis: Long): Int =
        if (minutes <= 0) 0 else ((minutes * 60_000L) / intervalMillis).toInt().coerceAtLeast(1)

    private fun pct(x: Double): String = String.format("%.2f%%", x * 100.0)
    private fun fmt(x: Double): String = String.format("%.4f", x)

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

    private data class OrderBookSeries(
        val imbalance10: DoubleArray,
        val spreadBps: DoubleArray,
        val available: BooleanArray
    )

    private fun buildOrderBookSeries(
        candles: List<Candle>,
        snapshots: List<OrderBookSnapshot>?
    ): OrderBookSeries? {
        if (snapshots.isNullOrEmpty()) return null
        val sorted = snapshots.sortedBy { it.timestamp }
        val n = candles.size
        val imbalance10 = DoubleArray(n) { 0.0 }
        val spreadBps = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val available = BooleanArray(n) { false }

        var snapIdx = 0
        var last: OrderBookSnapshot? = null

        for (i in 0 until n) {
            val t = candles[i].openTime
            while (snapIdx < sorted.size && sorted[snapIdx].timestamp <= t) {
                last = sorted[snapIdx]
                snapIdx++
            }
            if (last != null) {
                val mid = last.midPrice
                val spread = if (mid > 0.0) (last.spread / mid) * 10_000.0 else Double.POSITIVE_INFINITY
                imbalance10[i] = last.imbalance10
                spreadBps[i] = spread
                available[i] = true
            }
        }

        return OrderBookSeries(
            imbalance10 = imbalance10,
            spreadBps = spreadBps,
            available = available
        )
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
        val volume: DoubleArray,
        val orderBookImbalance10: DoubleArray,
        val orderBookSpreadBps: DoubleArray,
        val orderBookAvailable: BooleanArray
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
                ret30m = ret30,
                orderBookImbalance10 = orderBookImbalance10[end],
                orderBookSpreadBps = orderBookSpreadBps[end],
                orderBookAvailable = orderBookAvailable[end]
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
            fun from(
                candles: List<Candle>,
                intervalMillis: Long,
                orderBookSeries: OrderBookSeries?
            ): CandleSeries {
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

                val obImb = orderBookSeries?.imbalance10 ?: DoubleArray(n) { 0.0 }
                val obSpread = orderBookSeries?.spreadBps ?: DoubleArray(n) { Double.POSITIVE_INFINITY }
                val obAvail = orderBookSeries?.available ?: BooleanArray(n) { false }

                return CandleSeries(n, intervalMillis, ot, o, h, l, c, v, obImb, obSpread, obAvail)
            }
        }
    }
}
