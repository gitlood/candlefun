package com.example.algo.priortoprofitgroups

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.PatternBuckets
import com.example.platformutil.ProfitGroupMode
import com.example.algo.model.BreakoutGroup
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object EventStudyAnalyzer {

    private fun failGate(reason: String, shouldPrint: Boolean): EventStudyResult? {
        if (shouldPrint) println("EventStudyAnalyzer: SKIP ($reason)")
        lastResult = null
        return null
    }

    // Laplace smoothing so lift never becomes INF (and rare patterns don’t dominate)
    private const val LIFT_ALPHA = 0.5

    data class PatternRow(
        val key: String,
        val posCount: Int,
        val negCount: Int,
        val posRate: Double,
        val negRate: Double,
        val lift: Double,
        val avgPeakGain: Double,
        val avgHorizonGain: Double,
        val avgMaxDd: Double,
        val hit5: Int,
        val hit3: Int,
        val hit2: Int
    )

    data class EventStudyResult(
        val lookbackMinutes: Int,
        val lookbackBars: Int,
        val patternBars: Int,
        val contextBars: Int,
        val posGroupsTotal: Int,
        val negSampleTotal: Int,
        val posUsed: Int,
        val negUsed: Int,
        val topCollapsedByPos: List<PatternRow>,
        val topCollapsedByLift: List<PatternRow>,
        val topFullByLift: List<PatternRow>
    )

    private var lastResult: EventStudyResult? = null

    private data class PatternStats(
        var posCount: Int = 0,
        var negCount: Int = 0,
        var sumPeakGain: Double = 0.0,
        var sumHorizonGain: Double = 0.0,
        var sumMaxDd: Double = 0.0,
        var hit5: Int = 0,
        var hit3: Int = 0,
        var hit2: Int = 0
    ) {
        fun addPos(g: BreakoutGroup) {
            posCount++
            sumPeakGain += g.gainPctToPeakHigh
            sumHorizonGain += g.gainPctToHorizonClose
            sumMaxDd += g.maxDrawdownPct

            // BreakoutFinder/Group uses FRACTIONS (0.05, 0.03, 0.02)
            if (g.thresholdHit >= 0.05) hit5++
            if (g.thresholdHit >= 0.03) hit3++
            if (g.thresholdHit >= 0.02) hit2++
        }

        fun addNeg() {
            negCount++
        }

        fun mergeFrom(o: PatternStats) {
            posCount += o.posCount
            negCount += o.negCount
            sumPeakGain += o.sumPeakGain
            sumHorizonGain += o.sumHorizonGain
            sumMaxDd += o.sumMaxDd
            hit5 += o.hit5
            hit3 += o.hit3
            hit2 += o.hit2
        }

        fun avgPeak() = if (posCount == 0) 0.0 else sumPeakGain / posCount
        fun avgHorizon() = if (posCount == 0) 0.0 else sumHorizonGain / posCount
        fun avgDd() = if (posCount == 0) 0.0 else sumMaxDd / posCount
    }

    // ------------------------------------------------------------
    // Public API (config-driven)
    // ------------------------------------------------------------

    /** Preferred: pass the whole AlgoConfig from your sweep grid. */
    fun analyze(
        candles: List<Candle>,
        breakoutGroups: List<BreakoutGroup>,
        cfg: AlgoConfig,
    ) {
        if (cfg.profitGroup.mode == ProfitGroupMode.TP_HIT) {
            // quick gate: if you can’t even hit your highest threshold often enough,
            // event study patterns will be junk.
            val topThr = cfg.profitGroup.thresholds.maxOrNull() ?: 0.0
            if (topThr > 0.0) {
                val topHits = breakoutGroups.count { it.thresholdHit >= topThr }
                if (topHits < cfg.eventStudy.minPosCount) {
                    failGate(
                        "top-threshold hits too low (thr=${topThr}, hits=$topHits, min=${cfg.eventStudy.minPosCount})",
                        true
                    )
                    return
                }
            }
        }

        analyze(
            candles = candles,
            breakoutGroups = breakoutGroups,
            eventCfg = cfg.eventStudy,
            backtestCfg = cfg.backtest
        )
    }


    /** Lower-level overload if you only want the relevant config pieces. */
    fun analyze(
        candles: List<Candle>,
        breakoutGroups: List<BreakoutGroup>,
        eventCfg: EventStudyConfig,
        backtestCfg: BacktestConfig,
    ) {
        lastResult = analyzeInternal(
            candles = candles,
            groups = breakoutGroups,
            lookbackMinutes = backtestCfg.horizonMinutes,
            intervalMillis = backtestCfg.intervalMillis,
            negativeSampleEveryN = eventCfg.negativeSampleEveryN,
            seed = eventCfg.seed,
            patternBars = eventCfg.patternBars,
            contextBarsForBaselines = eventCfg.contextBars,
            topK = eventCfg.topK,
            minPosCount = eventCfg.minPosCount,
            maxNegatives = eventCfg.maxNegatives,
            shouldPrint = eventCfg.printReport,
            minNegSamplesToRun = eventCfg.minNegSamplesToRun,
            minPosEventsToRun = eventCfg.minPosEventsToRun,
            minDistinctFullKeysToRun = eventCfg.minDistinctFullKeysToRun
        )
    }

    fun lastTopFullKeysByLift(limit: Int): List<String> {
        val r = lastResult ?: return emptyList()
        return r.topFullByLift
            .take(limit)
            .map { it.key }
            .distinct()
    }

    /**
     * One-call helper aligned with sweeps:
     *   val patterns = runAndGetTopFullKeysByLift(candles, groups, cfg)
     */
    fun runAndGetTopFullKeysByLift(
        candles: List<Candle>,
        groups: List<BreakoutGroup>,
        cfg: AlgoConfig,
    ): List<String> {
        analyze(candles, groups, cfg)
        return lastTopFullKeysByLift(cfg.eventStudy.topK)
    }

    // ------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------

    private fun analyzeInternal(
        candles: List<Candle>,
        groups: List<BreakoutGroup>,
        lookbackMinutes: Int,
        intervalMillis: Long,
        negativeSampleEveryN: Int,
        seed: Int,
        patternBars: Int,
        contextBarsForBaselines: Int,
        topK: Int,
        minPosCount: Int,
        maxNegatives: Int,
        shouldPrint: Boolean,
        minPosEventsToRun: Int,
        minNegSamplesToRun: Int,
        minDistinctFullKeysToRun: Int,
    ): EventStudyResult? {

        if (candles.isEmpty() || groups.isEmpty()) {
            if (shouldPrint) {
                println("EventStudyAnalyzer: nothing to analyze (candles=${candles.size}, groups=${groups.size})")
            }
            return null
        }

        val lookbackBars = ((lookbackMinutes * 60_000L) / intervalMillis)
            .toInt()
            .coerceAtLeast(1)

        val minIndexNeeded = max(lookbackBars, patternBars + contextBarsForBaselines) + 1

        val posGroups = groups
            .filter { it.entryIndex >= minIndexNeeded }
            .distinctBy { it.entryIndex }

        if (posGroups.size < minPosEventsToRun) {
            return failGate(
                "posGroups after history filter too small: ${posGroups.size} < ${minPosEventsToRun}",
                shouldPrint
            )
        }

        val openTimeToIndex = HashMap<Long, Int>(candles.size * 2)
        candles.forEachIndexed { idx, c -> openTimeToIndex[c.openTime] = idx }

        val exclude = BooleanArray(candles.size)
        for (g in groups) {
            val start = (g.entryIndex - lookbackBars).coerceAtLeast(0)
            val endIdx = openTimeToIndex[g.windowEndOpenTime] ?: g.entryIndex
            val end = (endIdx + 1).coerceAtMost(candles.lastIndex)
            for (i in start..end) exclude[i] = true
        }

        val negIdx = ArrayList<Int>()
        val stepN = max(1, negativeSampleEveryN)
        for (i in minIndexNeeded until candles.size step stepN) {
            if (!exclude[i]) negIdx.add(i)
        }

        if (negIdx.size < minNegSamplesToRun) {
            return failGate(
                "neg sample too small after exclusions: ${negIdx.size} < ${minNegSamplesToRun}",
                shouldPrint
            )
        }

        val rnd = Random(seed)
        negIdx.shuffle(rnd)
        if (negIdx.size > maxNegatives) {
            negIdx.subList(maxNegatives, negIdx.size).clear()
        }

        val statsByPattern = HashMap<String, PatternStats>(4096)

        var posUsed = 0
        var negUsed = 0

        for (g in posGroups) {
            val key = patternKey(
                candles = candles,
                entryIndex = g.entryIndex,
                patternBars = patternBars,
                contextBars = contextBarsForBaselines
            ) ?: continue
            statsByPattern.getOrPut(key) { PatternStats() }.addPos(g)
            posUsed++
        }

        if (statsByPattern.size < minDistinctFullKeysToRun) {
            return failGate(
                "distinct full keys too small: ${statsByPattern.size} < ${minDistinctFullKeysToRun}",
                shouldPrint
            )
        }

        for (idx in negIdx) {
            val key = patternKey(
                candles = candles,
                entryIndex = idx,
                patternBars = patternBars,
                contextBars = contextBarsForBaselines
            ) ?: continue
            statsByPattern.getOrPut(key) { PatternStats() }.addNeg()
            negUsed++
        }

        val posTotal = posUsed.coerceAtLeast(1)
        val negTotal = negUsed.coerceAtLeast(1)

        val statsBySeq = HashMap<String, PatternStats>(2048)
        for ((k, st) in statsByPattern) {
            val ck = collapseKey(k)
            statsBySeq.getOrPut(ck) { PatternStats() }.mergeFrom(st)
        }

        fun pct(x: Double) = String.format("%.2f%%", x * 100.0)
        fun fmt(x: Double) = String.format("%.4f", x)

        fun toRow(key: String, st: PatternStats): PatternRow {
            val posRate = st.posCount.toDouble() / posTotal
            val negRate = st.negCount.toDouble() / negTotal
            val lift = liftSmoothed(st, posTotal, negTotal)
            return PatternRow(
                key = key,
                posCount = st.posCount,
                negCount = st.negCount,
                posRate = posRate,
                negRate = negRate,
                lift = lift,
                avgPeakGain = st.avgPeak(),
                avgHorizonGain = st.avgHorizon(),
                avgMaxDd = st.avgDd(),
                hit5 = st.hit5,
                hit3 = st.hit3,
                hit2 = st.hit2
            )
        }

        val filteredSeqEntries = statsBySeq.entries.filter { it.value.posCount >= minPosCount }

        val topCollapsedByPos = filteredSeqEntries
            .sortedWith(
                compareByDescending<Map.Entry<String, PatternStats>> { it.value.posCount }
                    .thenByDescending { liftSmoothed(it.value, posTotal, negTotal) }
            )
            .take(topK)
            .map { toRow(it.key, it.value) }

        val topCollapsedByLift = filteredSeqEntries
            .sortedWith(
                compareByDescending<Map.Entry<String, PatternStats>> {
                    liftSmoothed(
                        it.value,
                        posTotal,
                        negTotal
                    )
                }
                    .thenByDescending { it.value.posCount }
            )
            .take(topK)
            .map { toRow(it.key, it.value) }

        val filteredFullEntries = statsByPattern.entries.filter { it.value.posCount >= minPosCount }

        val topFullByLift = filteredFullEntries
            .sortedWith(
                compareByDescending<Map.Entry<String, PatternStats>> {
                    liftSmoothed(
                        it.value,
                        posTotal,
                        negTotal
                    )
                }
                    .thenByDescending { it.value.posCount }
            )
            .take(topK)
            .map { toRow(it.key, it.value) }

        if (shouldPrint) {
            println("============================================================")
            println("=== Event Study: Most Common Pre-Breakout Patterns ===")
            println("Lookback: ${lookbackMinutes}m (${lookbackBars} bars) | PatternBars: $patternBars | ContextBars: $contextBarsForBaselines")
            println("Positives (events): ${posGroups.size} (used=$posUsed) | Negatives (sampled): ${negIdx.size} (used=$negUsed)")
            println("Lift uses Laplace smoothing alpha=$LIFT_ALPHA (no INF)")
            println("------------------------------------------------------------")

            fun printRows(title: String, rows: List<PatternRow>) {
                println(title)
                if (rows.isEmpty()) {
                    println("  (no patterns met minPosCount=$minPosCount)")
                    return
                }
                rows.forEachIndexed { i, r ->
                    println(
                        "${(i + 1).toString().padStart(2)} " +
                                "pos=${r.posCount} (${pct(r.posRate)})  " +
                                "neg=${r.negCount} (${pct(r.negRate)})  " +
                                "lift=${fmt(r.lift)}  " +
                                "avgPk=${pct(r.avgPeakGain)} avgH=${pct(r.avgHorizonGain)} avgDD=${
                                    pct(
                                        r.avgMaxDd
                                    )
                                }  " +
                                "thr5=${r.hit5} thr3=${r.hit3} thr2=${r.hit2}  " +
                                "pattern=${r.key}"
                    )
                }
            }

            printRows("TOP $topK (COLLAPSED) BY POS COUNT", topCollapsedByPos)
            println("------------------------------------------------------------")
            printRows("TOP $topK (COLLAPSED) BY LIFT", topCollapsedByLift)
            println("------------------------------------------------------------")
            printRows("TOP $topK (FULL KEY) BY LIFT", topFullByLift)
            println("============================================================")
        }

        return EventStudyResult(
            lookbackMinutes = lookbackMinutes,
            lookbackBars = lookbackBars,
            patternBars = patternBars,
            contextBars = contextBarsForBaselines,
            posGroupsTotal = posGroups.size,
            negSampleTotal = negIdx.size,
            posUsed = posUsed,
            negUsed = negUsed,
            topCollapsedByPos = topCollapsedByPos,
            topCollapsedByLift = topCollapsedByLift,
            topFullByLift = topFullByLift
        )
    }

    private fun liftSmoothed(st: PatternStats, posTotal: Int, negTotal: Int): Double {
        val a = LIFT_ALPHA
        val posRate = (st.posCount + a) / (posTotal + 2.0 * a)
        val negRate = (st.negCount + a) / (negTotal + 2.0 * a)
        return posRate / negRate
    }

    private fun collapseKey(fullKey: String): String {
        val seq = fullKey.substringAfter("seq=").substringBefore("|")
        val last = fullKey.substringAfter("|last=").substringBefore("|", missingDelimiterValue = "")
        return if (last.isBlank()) "seq=$seq" else "seq=$seq|last=$last"
    }

    private fun patternKey(
        candles: List<Candle>,
        entryIndex: Int,
        patternBars: Int,
        contextBars: Int
    ): String? {
        val startPat = entryIndex - patternBars
        val startCtx = (entryIndex - contextBars).coerceAtLeast(0)
        if (startPat < 1 || entryIndex <= 1) return null

        fun d(s: String) = s.toDoubleOrNull()

        var rangeSum = 0.0
        var rangeN = 0
        var volSum = 0.0
        var volN = 0
        for (i in startCtx until entryIndex) {
            val h = d(candles[i].high) ?: continue
            val l = d(candles[i].low) ?: continue
            val v = d(candles[i].volume) ?: 0.0
            val r = h - l
            if (r > 0.0) {
                rangeSum += r; rangeN++
            }
            volSum += v; volN++
        }
        val meanRange = if (rangeN == 0) 0.0 else rangeSum / rangeN
        val meanVol = if (volN == 0) 0.0 else volSum / volN

        val seq = StringBuilder()
        var patRangeSum = 0.0
        var patVolSum = 0.0

        var lastShape = "N"
        for (i in startPat until entryIndex) {
            val o = d(candles[i].open) ?: return null
            val h = d(candles[i].high) ?: return null
            val l = d(candles[i].low) ?: return null
            val c = d(candles[i].close) ?: return null
            val v = d(candles[i].volume) ?: 0.0

            val range = h - l
            if (range <= 0.0) return null

            patRangeSum += range
            patVolSum += v

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

        val rangeRel = if (meanRange <= 0.0) Double.NaN else patRangeMean / meanRange
        val rangeB = PatternBuckets.bucketRangeRatio(rangeRel)

        val volRel = if (meanVol <= 0.0) Double.NaN else patVolMean / meanVol
        val volB = PatternBuckets.bucketVolumeRatio(volRel)

        val c0 = d(candles[startPat].close) ?: return null
        val c1 = d(candles[entryIndex - 1].close) ?: return null
        val ret = if (c0 == 0.0) 0.0 else (c1 / c0) - 1.0
        val retB = PatternBuckets.bucketRet(ret)

        return "seq=${seq}|ret=$retB|rng=$rangeB|vol=$volB|last=$lastShape"
    }
}
