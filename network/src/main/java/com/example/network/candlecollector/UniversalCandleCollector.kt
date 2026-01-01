package com.example.network.candlecollector

import com.example.network.BinanceUniverse
import com.example.network.candlecollector.helper.Intervals
import com.example.network.di.networkModule
import com.example.network.interfaces.BinanceApiService
import com.example.platform.model.enums.KlineInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

object UniversalCandleCollector {
    @JvmStatic
    fun main(args: Array<String>) {
        CandleCollectorRunner.main(args)
    }
}

object CandleCollectorRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val koin = startKoin { modules(networkModule) }.koin
        val api: BinanceApiService = koin.get()
        val universe: BinanceUniverse = koin.get()
        val config = CandleCollectorConfig.DEFAULT

        CandleStore(config.sqliteJdbcUrl).use { store ->
            store.init()
            val collector = CandleCollector(
                api = api,
                universe = universe,
                store = store,
                config = config
            )
            collector.run()
        }

        stopKoin()
    }
}

class CandleCollector(
    private val api: BinanceApiService,
    private val universe: BinanceUniverse,
    private val store: CandleStorePort,
    private val config: CandleCollectorConfig
) {

    suspend fun run() {
        val httpSem = Semaphore(config.maxConcurrentSymbolWorkers)

        val symbols = try {
            universe.fetchTopSymbols(config.universeConfig).map { it.symbol }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            println("Universe fetch failed: ${e.message}")
            return
        }

        printUniverse(symbols)
        println("Symbol   Int |    behind |   backfill | gaps | maxGapBars")
        cleanupOldData(symbols)

        coroutineScope {
            for (symbol in symbols) {
                for (intervalStr in config.intervals) {
                    val interval = KlineInterval.fromValue(intervalStr)
                    if (interval == null) {
                        println("Skipping unknown interval: $intervalStr")
                        continue
                    }
                    launch(Dispatchers.IO) {
                        collectSymbolIntervalForever(
                            httpSem = httpSem,
                            symbol = symbol,
                            interval = interval
                        )
                    }
                }
            }
        }
    }

    private suspend fun cleanupOldData(symbols: List<String>) {
        println("Starting cleanup of old data...")
        val now = System.currentTimeMillis()
        val cutoff = now - config.backfillDays * 24L * 60L * 60L * 1000L

        for (symbol in symbols) {
            for (intervalStr in config.intervals) {
                try {
                    store.deleteOldCandles(symbol, intervalStr, cutoff)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    println("Cleanup failed for $symbol $intervalStr: ${e.message}")
                }
            }
        }
        println("Cleanup finished.")
    }

    private suspend fun collectSymbolIntervalForever(
        httpSem: Semaphore,
        symbol: String,
        interval: KlineInterval
    ) {
        val intervalStr = interval.value
        val log = SymbolLogger(
            symbol,
            intervalStr,
            config.logStatusEveryMs,
            config.logErrorsEveryMs
        )

        val intervalMs = Intervals.toMs(intervalStr)
        val now0 = Instant.now().toEpochMilli()
        val desiredStart = floorToInterval(
            now0 - config.backfillDays * 24L * 60L * 60L * 1000L,
            intervalMs
        )

        val dbMin = store.getMinOpenTime(symbol, intervalStr)
        val dbMax = store.getMaxOpenTime(symbol, intervalStr)

        val nowMs = System.currentTimeMillis()
        val minutesBehind = dbMax?.let { ((nowMs - it) / 60_000L).coerceAtLeast(0L) }
            ?: (config.backfillDays * 24L * 60L)
        val backfillMin = when {
            dbMin == null -> config.backfillDays * 24L * 60L
            dbMin > desiredStart -> (dbMin - desiredStart) / 60_000L
            else -> 0L
        }

        val recent = store.getRecentOpenTimes(symbol, intervalStr, 500)
        val gapStats = computeGapStats(recent, intervalMs)
        val gapsStr = gapStats?.gaps?.toString() ?: "na"
        val maxGapStr = gapStats?.maxGapBars?.toString() ?: "na"

        log.info(
            "behind=%6dm | backfill=%6dm | gaps=%3s | maxGapBars=%4s".format(
                minutesBehind,
                backfillMin,
                gapsStr,
                maxGapStr
            )
        )

        if (dbMin != null && dbMin > desiredStart) {
            log.info("backfill-backwards | backfill=%6dm".format(backfillMin))
            backfillBackwards(
                httpSem = httpSem,
                symbol = symbol,
                interval = interval,
                intervalMs = intervalMs,
                fromExclusive = dbMin,
                toInclusive = desiredStart,
                log = log
            )
        }

        val dbMaxAfter = store.getMaxOpenTime(symbol, intervalStr)
        val startTimeForward = if (dbMaxAfter != null) dbMaxAfter + intervalMs else desiredStart

        log.info("forward-start | behind=%6dm (resume/continue)".format(minutesBehind))

        var nextStart = startTimeForward
        var backoffMs = 1_000L

        while (currentCoroutineContext().isActive) {
            try {
                val now = Instant.now().toEpochMilli()

                if (nextStart > now) {
                    val waitMs = nextStart - now + 1000L
                    val sleep = min(waitMs, config.caughtUpPollMs)
                    if (sleep > 0) delay(sleep)
                }

                val klines = httpSem.withPermit {
                    api.getKlines(
                        symbol = symbol,
                        interval = interval,
                        limit = 1000,
                        startTime = nextStart,
                        endTime = null
                    )
                }

                val closed = klines.filter { it.closeTime <= System.currentTimeMillis() }

                if (closed.isNotEmpty()) {
                    store.insertCandles(symbol, intervalStr, closed)

                    val first = closed.first()
                    val last = closed.last()

                    nextStart = last.openTime + intervalMs
                    backoffMs = 1_000L

                    log.info(
                        "inserted=%4d | range=%s→%s | next=%s".format(
                            closed.size,
                            Instant.ofEpochMilli(first.openTime),
                            Instant.ofEpochMilli(last.openTime),
                            Instant.ofEpochMilli(nextStart)
                        )
                    )

                    if (klines.size < 1000) {
                        val timeBehind = System.currentTimeMillis() - last.closeTime
                        if (timeBehind > intervalMs * 2) {
                            delay(config.fastSyncDelayMs)
                        } else {
                            log.info("caught up; polling")
                            delay(config.caughtUpPollMs)
                        }
                    } else {
                        delay(config.fastSyncDelayMs)
                    }
                } else {
                    log.info("no new closed candles; polling")
                    delay(config.caughtUpPollMs)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.error("${e.message} (backoff=${backoffMs}ms)")
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, 60_000L)
            }
        }
    }

    private suspend fun backfillBackwards(
        httpSem: Semaphore,
        symbol: String,
        interval: KlineInterval,
        intervalMs: Long,
        fromExclusive: Long,
        toInclusive: Long,
        log: SymbolLogger
    ) {
        var cursorExclusive = fromExclusive

        while (currentCoroutineContext().isActive && cursorExclusive > toInclusive + intervalMs) {
            val endTime = cursorExclusive - 1
            val windowStart = max(toInclusive, endTime - (intervalMs * 999L))

            val page = try {
                httpSem.withPermit {
                    api.getKlines(
                        symbol = symbol,
                        interval = interval,
                        limit = 1000,
                        startTime = windowStart,
                        endTime = endTime
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.error("backfill-backwards error: ${e.message}")
                delay(2_000)
                continue
            }

            if (page.isEmpty()) {
                cursorExclusive = windowStart
                continue
            }

            store.insertCandles(symbol, interval.value, page)

            val first = page.first()
            val last = page.last()

            log.info(
                "backfilled=${page.size} " +
                    "range=${Instant.ofEpochMilli(first.openTime)}→${Instant.ofEpochMilli(last.openTime)}"
            )

            delay(config.fastSyncDelayMs)

            cursorExclusive = first.openTime
        }
    }

    private fun printUniverse(symbols: List<String>) {
        println("Universe symbols (${symbols.size}):")
        val cols = 4
        val width = 12
        symbols.chunked(cols).forEach { chunk ->
            val line = chunk.joinToString("") { "%-${width}s".format(it) }
            println("  $line".trimEnd())
        }
    }

    private fun floorToInterval(tsMs: Long, intervalMs: Long): Long {
        return (tsMs / intervalMs) * intervalMs
    }

    private fun computeGapStats(times: List<Long>, intervalMs: Long): GapStats? {
        if (times.size < 2) return null
        var gaps = 0
        var maxGapBars = 0L
        for (i in 0 until times.size - 1) {
            val a = times[i]
            val b = times[i + 1]
            val diff = a - b
            if (diff != intervalMs) {
                gaps++
                val bars = diff / intervalMs
                if (bars > maxGapBars) maxGapBars = bars
            }
        }
        return GapStats(gaps, maxGapBars)
    }

    private data class GapStats(val gaps: Int, val maxGapBars: Long)
}
