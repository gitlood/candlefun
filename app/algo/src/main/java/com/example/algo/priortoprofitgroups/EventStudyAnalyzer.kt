package com.example.algo.priortoprofitgroups

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.PatternBuckets
import com.example.platformutil.ProfitGroupMode
import com.example.algo.model.BreakoutGroup
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
        val pValue: Double,
        val regimeBuckets: Int,
        val avgNet: Double,
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
        val topFullByLift: List<PatternRow>,
        val topFullByEdge: List<PatternRow>
    )

    private var lastResult: EventStudyResult? = null

    private data class PatternStats(
        var posCount: Int = 0,
        var negCount: Int = 0,
        var sumPeakGain: Double = 0.0,
        var sumHorizonGain: Double = 0.0,
        var sumMaxDd: Double = 0.0,
        var sumNet: Double = 0.0,
        var hit5: Int = 0,
        var hit3: Int = 0,
        var hit2: Int = 0
    ) {
        fun addPos(g: BreakoutGroup) {
            posCount++
            sumPeakGain += g.gainPctToPeakHigh
            sumHorizonGain += g.gainPctToHorizonClose
            sumMaxDd += g.maxDrawdownPct
            sumNet += g.netPct

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
            sumNet += o.sumNet
            hit5 += o.hit5
            hit3 += o.hit3
            hit2 += o.hit2
        }

        fun avgPeak() = if (posCount == 0) 0.0 else sumPeakGain / posCount
        fun avgHorizon() = if (posCount == 0) 0.0 else sumHorizonGain / posCount
        fun avgDd() = if (posCount == 0) 0.0 else sumMaxDd / posCount
        fun avgNet() = if (posCount == 0) 0.0 else sumNet / posCount
    }

    private data class FoldStats(
        val posCounts: IntArray,
        val netSums: DoubleArray
    ) {
        constructor(folds: Int) : this(IntArray(folds), DoubleArray(folds))

        fun addPos(fold: Int, netPct: Double) {
            if (fold < 0 || fold >= posCounts.size) return
            posCounts[fold]++
            netSums[fold] += netPct
        }

        fun mergeFrom(o: FoldStats) {
            for (i in posCounts.indices) {
                posCounts[i] += o.posCounts.getOrElse(i) { 0 }
                netSums[i] += o.netSums.getOrElse(i) { 0.0 }
            }
        }

        fun stableFoldCount(minPosPerFold: Int, minNetEdge: Double): Int {
            var ok = 0
            for (i in posCounts.indices) {
                val c = posCounts[i]
                if (c < minPosPerFold) continue
                val avgNet = netSums[i] / c.toDouble()
                if (avgNet >= minNetEdge) ok++
            }
            return ok
        }

        fun meetsStability(requiredFolds: Int, minPosPerFold: Int, minNetEdge: Double): Boolean {
            if (requiredFolds <= 1) return true
            return stableFoldCount(minPosPerFold, minNetEdge) >= requiredFolds
        }
    }

    private data class RegimeStats(
        val bucketCounts: HashMap<String, Int> = HashMap(),
        val netSums: HashMap<String, Double> = HashMap()
    ) {
        fun add(bucket: String, netPct: Double) {
            bucketCounts[bucket] = (bucketCounts[bucket] ?: 0) + 1
            netSums[bucket] = (netSums[bucket] ?: 0.0) + netPct
        }

        fun mergeFrom(o: RegimeStats) {
            for ((k, v) in o.bucketCounts) {
                bucketCounts[k] = (bucketCounts[k] ?: 0) + v
            }
            for ((k, v) in o.netSums) {
                netSums[k] = (netSums[k] ?: 0.0) + v
            }
        }

        fun qualifyingBucketCount(minPosPerBucket: Int, minNetEdge: Double): Int {
            var ok = 0
            for ((bucket, count) in bucketCounts) {
                if (count < minPosPerBucket) continue
                val sum = netSums[bucket] ?: 0.0
                val avg = sum / count.toDouble()
                if (avg >= minNetEdge) ok++
            }
            return ok
        }
    }

    private data class NegCandidate(
        val index: Int,
        val patternKey: String,
        val regimeKey: String
    )

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
            minNetEdge = eventCfg.minNetEdge,
            embargoMinutes = eventCfg.embargoMinutes,
            stabilityFolds = eventCfg.stabilityFolds,
            minStableFolds = eventCfg.minStableFolds,
            minPosPerFold = eventCfg.minPosPerFold,
            maxFdr = eventCfg.maxFdr,
            regimeMinBuckets = eventCfg.regimeMinBuckets,
            regimeMinPosPerBucket = eventCfg.regimeMinPosPerBucket,
            minNegSamplesToRun = eventCfg.minNegSamplesToRun,
            minPosEventsToRun = eventCfg.minPosEventsToRun,
            minDistinctFullKeysToRun = eventCfg.minDistinctFullKeysToRun
        )
    }

    /**
     * Full result helper for sweep/reporting use cases.
     * Returns the computed result (and updates lastResult for compatibility).
     */
    fun analyzeForResult(
        candles: List<Candle>,
        breakoutGroups: List<BreakoutGroup>,
        cfg: AlgoConfig,
    ): EventStudyResult? {
        val result = analyzeInternal(
            candles = candles,
            groups = breakoutGroups,
            lookbackMinutes = cfg.backtest.horizonMinutes,
            intervalMillis = cfg.backtest.intervalMillis,
            negativeSampleEveryN = cfg.eventStudy.negativeSampleEveryN,
            seed = cfg.eventStudy.seed,
            patternBars = cfg.eventStudy.patternBars,
            contextBarsForBaselines = cfg.eventStudy.contextBars,
            topK = cfg.eventStudy.topK,
            minPosCount = cfg.eventStudy.minPosCount,
            maxNegatives = cfg.eventStudy.maxNegatives,
            shouldPrint = cfg.eventStudy.printReport,
            minNetEdge = cfg.eventStudy.minNetEdge,
            embargoMinutes = cfg.eventStudy.embargoMinutes,
            stabilityFolds = cfg.eventStudy.stabilityFolds,
            minStableFolds = cfg.eventStudy.minStableFolds,
            minPosPerFold = cfg.eventStudy.minPosPerFold,
            maxFdr = cfg.eventStudy.maxFdr,
            regimeMinBuckets = cfg.eventStudy.regimeMinBuckets,
            regimeMinPosPerBucket = cfg.eventStudy.regimeMinPosPerBucket,
            minNegSamplesToRun = cfg.eventStudy.minNegSamplesToRun,
            minPosEventsToRun = cfg.eventStudy.minPosEventsToRun,
            minDistinctFullKeysToRun = cfg.eventStudy.minDistinctFullKeysToRun
        )
        lastResult = result
        return result
    }

    fun lastResultSnapshot(): EventStudyResult? = lastResult

    fun lastTopFullKeysByLift(limit: Int): List<String> {
        val r = lastResult ?: return emptyList()
        return r.topFullByLift
            .take(limit)
            .map { it.key }
            .distinct()
    }

    fun lastTopFullKeysByEdge(limit: Int): List<String> {
        val r = lastResult ?: return emptyList()
        return r.topFullByEdge
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

    /**
     * Profit-aligned helper (net edge first, then lift).
     */
    fun runAndGetTopFullKeysByEdge(
        candles: List<Candle>,
        groups: List<BreakoutGroup>,
        cfg: AlgoConfig,
    ): List<String> {
        analyze(candles, groups, cfg)
        return lastTopFullKeysByEdge(cfg.eventStudy.topK)
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
        minNetEdge: Double,
        embargoMinutes: Int,
        stabilityFolds: Int,
        minStableFolds: Int,
        minPosPerFold: Int,
        maxFdr: Double,
        regimeMinBuckets: Int,
        regimeMinPosPerBucket: Int,
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
        val embargoBars = ((embargoMinutes * 60_000L) / intervalMillis)
            .toInt()
            .coerceAtLeast(0)

        val minIndexNeeded = max(lookbackBars, patternBars + contextBarsForBaselines) + 1

        val posGroups = groups
            .filter { it.entryIndex >= minIndexNeeded && it.netPct >= minNetEdge }
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
        for (g in posGroups) {
            val start = (g.entryIndex - lookbackBars).coerceAtLeast(0)
            val endIdx = openTimeToIndex[g.windowEndOpenTime] ?: g.entryIndex
            val end = (endIdx + 1 + embargoBars).coerceAtMost(candles.lastIndex)
            for (i in start..end) exclude[i] = true
        }

        val negIdx = ArrayList<Int>()
        val stepN = max(1, negativeSampleEveryN)
        for (i in minIndexNeeded until candles.size step stepN) {
            if (!exclude[i]) negIdx.add(i)
        }

        val rnd = Random(seed)

        val statsByPattern = HashMap<String, PatternStats>(4096)
        val posRegimeCounts = HashMap<String, Int>(256)
        val regimeStatsByPattern = HashMap<String, RegimeStats>(2048)

        var posUsed = 0
        var negUsed = 0

        val foldCount = stabilityFolds.coerceAtLeast(1)
        val requiredStableFolds = min(minStableFolds, foldCount)
        val foldSpan = (candles.size - minIndexNeeded).coerceAtLeast(1)
        fun foldIndex(entryIndex: Int): Int {
            val raw = ((entryIndex - minIndexNeeded).toDouble() / foldSpan.toDouble()) * foldCount
            return raw.toInt().coerceIn(0, foldCount - 1)
        }

        val foldStatsByPattern = if (foldCount > 1) HashMap<String, FoldStats>(2048) else null

        for (g in posGroups) {
            val key = patternKey(
                candles = candles,
                entryIndex = g.entryIndex,
                patternBars = patternBars,
                contextBars = contextBarsForBaselines
            ) ?: continue
            statsByPattern.getOrPut(key) { PatternStats() }.addPos(g)
            val regimeKey = regimeKeyFromPatternKey(key)
            if (regimeKey != null) {
                posRegimeCounts[regimeKey] = (posRegimeCounts[regimeKey] ?: 0) + 1
            }
            val trendVolKey = trendVolBucketFromPatternKey(key)
            if (trendVolKey != null) {
                regimeStatsByPattern.getOrPut(key) { RegimeStats() }.add(trendVolKey, g.netPct)
            }
            if (foldStatsByPattern != null) {
                val fold = foldIndex(g.entryIndex)
                foldStatsByPattern.getOrPut(key) { FoldStats(foldCount) }.addPos(fold, g.netPct)
            }
            posUsed++
        }

        if (statsByPattern.size < minDistinctFullKeysToRun) {
            return failGate(
                "distinct full keys too small: ${statsByPattern.size} < ${minDistinctFullKeysToRun}",
                shouldPrint
            )
        }

        val negCandidatesByRegime = HashMap<String, MutableList<NegCandidate>>(256)
        for (idx in negIdx) {
            val key = patternKey(
                candles = candles,
                entryIndex = idx,
                patternBars = patternBars,
                contextBars = contextBarsForBaselines
            ) ?: continue
            val regimeKey = regimeKeyFromPatternKey(key) ?: continue
            negCandidatesByRegime.getOrPut(regimeKey) { mutableListOf() }
                .add(NegCandidate(idx, key, regimeKey))
        }

        val totalCandidates = negCandidatesByRegime.values.sumOf { it.size }
        if (totalCandidates < minNegSamplesToRun) {
            return failGate(
                "neg sample too small after exclusions: ${totalCandidates} < ${minNegSamplesToRun}",
                shouldPrint
            )
        }

        val maxNegCap = min(maxNegatives, totalCandidates)
        val posRegimeTotal = posRegimeCounts.values.sum().coerceAtLeast(1)
        val selected = ArrayList<NegCandidate>(maxNegCap)
        val selectedIdx = HashSet<Int>(maxNegCap * 2)

        for ((regime, posCount) in posRegimeCounts) {
            val candidates = negCandidatesByRegime[regime] ?: continue
            if (candidates.isEmpty()) continue
            candidates.shuffle(rnd)
            val target = ((maxNegCap.toDouble() * posCount.toDouble()) / posRegimeTotal.toDouble())
                .roundToInt()
                .coerceAtLeast(0)
            val takeN = min(target, candidates.size)
            for (i in 0 until takeN) {
                val c = candidates[i]
                if (selectedIdx.add(c.index)) selected.add(c)
            }
            if (selected.size >= maxNegCap) break
        }

        val minNeeded = min(minNegSamplesToRun, maxNegCap)
        if (selected.size < minNeeded) {
            val remaining = ArrayList<NegCandidate>(maxNegCap)
            for (candidates in negCandidatesByRegime.values) {
                for (c in candidates) {
                    if (!selectedIdx.contains(c.index)) remaining.add(c)
                }
            }
            remaining.shuffle(rnd)
            val addN = min(minNeeded - selected.size, remaining.size)
            for (i in 0 until addN) {
                val c = remaining[i]
                if (selectedIdx.add(c.index)) selected.add(c)
            }
        }

        if (selected.size < minNegSamplesToRun) {
            return failGate(
                "neg sample too small after stratified sampling: ${selected.size} < ${minNegSamplesToRun}",
                shouldPrint
            )
        }

        for (cand in selected) {
            statsByPattern.getOrPut(cand.patternKey) { PatternStats() }.addNeg()
            negUsed++
        }

        val posTotal = posUsed.coerceAtLeast(1)
        val negTotal = negUsed.coerceAtLeast(1)

        val statsBySeq = HashMap<String, PatternStats>(2048)
        for ((k, st) in statsByPattern) {
            val ck = collapseKey(k)
            statsBySeq.getOrPut(ck) { PatternStats() }.mergeFrom(st)
        }

        val stableFullKeys: Set<String>
        val stableSeqKeys: Set<String>
        if (foldStatsByPattern != null && requiredStableFolds > 1) {
            stableFullKeys = foldStatsByPattern
                .filterValues { it.meetsStability(requiredStableFolds, minPosPerFold, minNetEdge) }
                .keys

            val foldStatsBySeq = HashMap<String, FoldStats>(1024)
            for ((k, fs) in foldStatsByPattern) {
                val ck = collapseKey(k)
                foldStatsBySeq.getOrPut(ck) { FoldStats(foldCount) }.mergeFrom(fs)
            }
            stableSeqKeys = foldStatsBySeq
                .filterValues { it.meetsStability(requiredStableFolds, minPosPerFold, minNetEdge) }
                .keys
        } else {
            stableFullKeys = statsByPattern.keys
            stableSeqKeys = statsBySeq.keys
        }

        val regimeStatsBySeq = HashMap<String, RegimeStats>(1024)
        for ((k, rs) in regimeStatsByPattern) {
            val ck = collapseKey(k)
            regimeStatsBySeq.getOrPut(ck) { RegimeStats() }.mergeFrom(rs)
        }

        val regimeBucketsByFullKey = regimeStatsByPattern.mapValues {
            it.value.qualifyingBucketCount(regimeMinPosPerBucket, minNetEdge)
        }
        val regimeBucketsBySeqKey = regimeStatsBySeq.mapValues {
            it.value.qualifyingBucketCount(regimeMinPosPerBucket, minNetEdge)
        }
        val regimeMin = regimeMinBuckets.coerceAtLeast(1)
        val regimeFullKeys = if (regimeMin > 1) {
            regimeBucketsByFullKey.filterValues { it >= regimeMin }.keys
        } else {
            statsByPattern.keys
        }
        val regimeSeqKeys = if (regimeMin > 1) {
            regimeBucketsBySeqKey.filterValues { it >= regimeMin }.keys
        } else {
            statsBySeq.keys
        }

        val pValueByFullKey = statsByPattern.mapValues {
            pValueTwoProp(it.value.posCount, it.value.negCount, posTotal, negTotal)
        }
        val pValueBySeqKey = statsBySeq.mapValues {
            pValueTwoProp(it.value.posCount, it.value.negCount, posTotal, negTotal)
        }
        val fdrThresholdFull = fdrThreshold(pValueByFullKey.values, maxFdr)
        val fdrThresholdSeq = fdrThreshold(pValueBySeqKey.values, maxFdr)
        val fdrFullKeys = if (fdrThresholdFull.isFinite()) {
            pValueByFullKey.filterValues { it <= fdrThresholdFull }.keys
        } else {
            statsByPattern.keys
        }
        val fdrSeqKeys = if (fdrThresholdSeq.isFinite()) {
            pValueBySeqKey.filterValues { it <= fdrThresholdSeq }.keys
        } else {
            statsBySeq.keys
        }

        fun pct(x: Double) = String.format("%.2f%%", x * 100.0)
        fun fmt(x: Double) = String.format("%.4f", x)

        fun toRow(key: String, st: PatternStats, pValue: Double, regimeBuckets: Int): PatternRow {
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
                pValue = pValue,
                regimeBuckets = regimeBuckets,
                avgNet = st.avgNet(),
                avgPeakGain = st.avgPeak(),
                avgHorizonGain = st.avgHorizon(),
                avgMaxDd = st.avgDd(),
                hit5 = st.hit5,
                hit3 = st.hit3,
                hit2 = st.hit2
            )
        }

        val filteredSeqEntries = statsBySeq.entries.filter {
            it.value.posCount >= minPosCount &&
                stableSeqKeys.contains(it.key) &&
                regimeSeqKeys.contains(it.key) &&
                fdrSeqKeys.contains(it.key)
        }

        val topCollapsedByPos = filteredSeqEntries
            .sortedWith(
                compareByDescending<Map.Entry<String, PatternStats>> { it.value.posCount }
                    .thenByDescending { liftSmoothed(it.value, posTotal, negTotal) }
                    .thenByDescending { regimeBucketsBySeqKey[it.key] ?: 0 }
            )
            .take(topK)
            .map { toRow(it.key, it.value, pValueBySeqKey[it.key] ?: 1.0, regimeBucketsBySeqKey[it.key] ?: 0) }

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
                    .thenByDescending { regimeBucketsBySeqKey[it.key] ?: 0 }
            )
            .take(topK)
            .map { toRow(it.key, it.value, pValueBySeqKey[it.key] ?: 1.0, regimeBucketsBySeqKey[it.key] ?: 0) }

        val filteredFullEntries = statsByPattern.entries.filter {
            it.value.posCount >= minPosCount &&
                stableFullKeys.contains(it.key) &&
                regimeFullKeys.contains(it.key) &&
                fdrFullKeys.contains(it.key)
        }

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
                    .thenByDescending { it.value.avgNet() }
                    .thenByDescending { regimeBucketsByFullKey[it.key] ?: 0 }
            )
            .take(topK)
            .map { toRow(it.key, it.value, pValueByFullKey[it.key] ?: 1.0, regimeBucketsByFullKey[it.key] ?: 0) }

        val topFullByEdge = filteredFullEntries
            .sortedWith(
                compareByDescending<Map.Entry<String, PatternStats>> { it.value.avgNet() }
                    .thenByDescending { liftSmoothed(it.value, posTotal, negTotal) }
                    .thenByDescending { it.value.posCount }
                    .thenByDescending { regimeBucketsByFullKey[it.key] ?: 0 }
            )
            .take(topK)
            .map { toRow(it.key, it.value, pValueByFullKey[it.key] ?: 1.0, regimeBucketsByFullKey[it.key] ?: 0) }

        if (shouldPrint) {
            println("============================================================")
            println("=== Event Study: Most Common Pre-Breakout Patterns ===")
            println("Lookback: ${lookbackMinutes}m (${lookbackBars} bars) | PatternBars: $patternBars | ContextBars: $contextBarsForBaselines")
            println("Positives (events): ${posGroups.size} (used=$posUsed) | Negatives (candidates): ${totalCandidates} (used=$negUsed)")
            println("Positive filter: net >= ${pct(minNetEdge)}")
            println("Embargo after positives: ${embargoMinutes}m (${embargoBars} bars)")
            if (foldCount > 1 && requiredStableFolds > 1) {
                println("Stability filter: folds=$foldCount minStableFolds=$requiredStableFolds minPosPerFold=$minPosPerFold")
            }
            if (maxFdr > 0.0 && maxFdr < 1.0) {
                println("FDR filter: maxFdr=$maxFdr (full<=${fmt(fdrThresholdFull)} seq<=${fmt(fdrThresholdSeq)})")
            }
            if (regimeMin > 1) {
                println("Regime filter: minBuckets=$regimeMin minPosPerBucket=$regimeMinPosPerBucket")
            }
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
                                "p=${fmt(r.pValue)}  " +
                                "rg=${r.regimeBuckets}  " +
                                "avgNet=${pct(r.avgNet)} avgPk=${pct(r.avgPeakGain)} avgH=${pct(r.avgHorizonGain)} " +
                                "avgDD=${pct(r.avgMaxDd)}  " +
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
            println("------------------------------------------------------------")
            printRows("TOP $topK (FULL KEY) BY NET EDGE", topFullByEdge)
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
            topFullByLift = topFullByLift,
            topFullByEdge = topFullByEdge
        )
    }

    private fun liftSmoothed(st: PatternStats, posTotal: Int, negTotal: Int): Double {
        val a = LIFT_ALPHA
        val posRate = (st.posCount + a) / (posTotal + 2.0 * a)
        val negRate = (st.negCount + a) / (negTotal + 2.0 * a)
        return posRate / negRate
    }

    private fun pValueTwoProp(posCount: Int, negCount: Int, posTotal: Int, negTotal: Int): Double {
        if (posTotal <= 0 || negTotal <= 0) return 1.0
        val p1 = posCount.toDouble() / posTotal.toDouble()
        val p2 = negCount.toDouble() / negTotal.toDouble()
        val pooled = (posCount + negCount).toDouble() / (posTotal + negTotal).toDouble()
        val se = sqrt(pooled * (1.0 - pooled) * (1.0 / posTotal + 1.0 / negTotal))
        if (!se.isFinite() || se <= 0.0) return 1.0
        val z = (p1 - p2) / se
        val p = 2.0 * (1.0 - normalCdf(abs(z)))
        return p.coerceIn(0.0, 1.0)
    }

    private fun fdrThreshold(pValues: Collection<Double>, maxFdr: Double): Double {
        if (pValues.isEmpty()) return Double.POSITIVE_INFINITY
        if (maxFdr <= 0.0 || maxFdr >= 1.0) return Double.POSITIVE_INFINITY
        val sorted = pValues.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return Double.POSITIVE_INFINITY
        var thresh = -1.0
        val m = sorted.size
        for (i in sorted.indices) {
            val p = sorted[i]
            val bound = ((i + 1).toDouble() / m.toDouble()) * maxFdr
            if (p <= bound) thresh = p
        }
        return if (thresh < 0.0) 0.0 else thresh
    }

    private fun normalCdf(x: Double): Double {
        return 0.5 * (1.0 + erfApprox(x / sqrt(2.0)))
    }

    private fun erfApprox(x: Double): Double {
        val t = 1.0 / (1.0 + 0.5 * abs(x))
        val tau = t * exp(
            -x * x - 1.26551223 +
                1.00002368 * t +
                0.37409196 * t * t +
                0.09678418 * t * t * t -
                0.18628806 * t * t * t * t +
                0.27886807 * t * t * t * t * t -
                1.13520398 * t * t * t * t * t * t +
                1.48851587 * t * t * t * t * t * t * t -
                0.82215223 * t * t * t * t * t * t * t * t +
                0.17087277 * t * t * t * t * t * t * t * t * t
        )
        return if (x >= 0) 1.0 - tau else tau - 1.0
    }

    private fun collapseKey(fullKey: String): String {
        val seq = fullKey.substringAfter("seq=").substringBefore("|")
        val last = fullKey.substringAfter("|last=").substringBefore("|", missingDelimiterValue = "")
        return if (last.isBlank()) "seq=$seq" else "seq=$seq|last=$last"
    }

    private fun regimeKeyFromPatternKey(fullKey: String): String? {
        val ret = fullKey.substringAfter("|ret=").substringBefore("|", missingDelimiterValue = "")
        val rng = fullKey.substringAfter("|rng=").substringBefore("|", missingDelimiterValue = "")
        val vol = fullKey.substringAfter("|vol=").substringBefore("|", missingDelimiterValue = "")
        if (ret.isBlank() || rng.isBlank() || vol.isBlank()) return null
        return "ret=$ret|rng=$rng|vol=$vol"
    }

    private fun trendVolBucketFromPatternKey(fullKey: String): String? {
        val ret = fullKey.substringAfter("|ret=").substringBefore("|", missingDelimiterValue = "")
        val rng = fullKey.substringAfter("|rng=").substringBefore("|", missingDelimiterValue = "")
        if (ret.isBlank() || rng.isBlank()) return null
        return "trend=$ret|vol=$rng"
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
