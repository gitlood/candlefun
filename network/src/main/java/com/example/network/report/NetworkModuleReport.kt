// ------------------------------------------------------------------
// FILE: com/example/network/report/NetworkModuleReport.kt
// ------------------------------------------------------------------

package com.example.network.report

import com.example.network.candlecollector.CandleCollectorConfig
import com.example.network.candlecollector.helper.Intervals
import com.example.network.config.BinanceEndpoints
import com.example.network.config.BinanceEnvs
import com.example.network.di.networkModule
import com.example.network.dto.OrderBookDto
import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsDepthUpdateData
import com.example.network.dto.WsKlineData
import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.ExchangeInfoService
import com.example.network.interfaces.TickerService
import com.example.network.interfaces.TradeService
import com.example.platform.model.OrderBook
import com.example.platform.model.enums.KlineInterval
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.sql.DriverManager
import java.time.Instant
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis

object NetworkModuleReport {

    private enum class Status { PASS, WARN, FAIL }

    private data class CheckResult(
        val name: String,
        val status: Status,
        val ms: Long,
        val detail: String,
        val error: Throwable? = null
    )

    private data class SymbolCoverageRow(
        val symbol: String,
        val interval: String,
        val rows: Long,
        val minOpenTime: Long?,
        val maxOpenTime: Long?,
        val ageMin: Long?
    )

    @Serializable
    private data class ServerTimeDto(val serverTime: Long)

    // ---------- Local L2 book (minimal) ----------
    private class LocalL2Book {
        // Price -> Qty
        private val bids = TreeMap<Double, Double>(compareByDescending { it })
        private val asks = TreeMap<Double, Double>()
        var lastUpdateId: Long = 0L
            private set

        fun loadSnapshot(snapshot: OrderBook) {
            bids.clear()
            asks.clear()
            snapshot.bids.forEach { if (it.quantity > 0) bids[it.price] = it.quantity }
            snapshot.asks.forEach { if (it.quantity > 0) asks[it.price] = it.quantity }
            lastUpdateId = snapshot.lastUpdateId
        }

        fun bestBid(): Double? = bids.firstKeyOrNull()
        fun bestAsk(): Double? = asks.firstKeyOrNull()

        fun applyBridge(update: WsDepthUpdateData) {
            // Overlap-tolerant: may start before lastUpdateId+1; allowed as long as it spans it (or is newer).
            applyDelta(update)
            lastUpdateId = update.finalUpdateId
        }

        /**
         * Overlap-tolerant sequencing:
         * - Ignore updates that are entirely older than our book (handled by caller typically)
         * - Accept if it spans expected = lastUpdateId+1: firstUpdateId <= expected <= finalUpdateId
         * - Otherwise sequence is broken.
         */
        fun applyNext(update: WsDepthUpdateData): Boolean {
            val expected = lastUpdateId + 1

            // If this update is entirely behind, it's effectively a duplicate (caller usually filters these out)
            if (update.finalUpdateId < expected) return true

            // Accept overlap as long as expected is inside the [U..u] span
            if (update.firstUpdateId <= expected && update.finalUpdateId >= expected) {
                applyDelta(update)
                lastUpdateId = update.finalUpdateId
                return true
            }

            return false
        }

        private fun applyDelta(update: WsDepthUpdateData) {
            // bids/asks: [["price","qty"], ...]
            for (lvl in update.bids) {
                if (lvl.size < 2) continue
                val p = lvl[0].toDoubleOrNull() ?: continue
                val q = lvl[1].toDoubleOrNull() ?: continue
                if (q <= 0.0) bids.remove(p) else bids[p] = q
            }
            for (lvl in update.asks) {
                if (lvl.size < 2) continue
                val p = lvl[0].toDoubleOrNull() ?: continue
                val q = lvl[1].toDoubleOrNull() ?: continue
                if (q <= 0.0) asks.remove(p) else asks[p] = q
            }
        }

        private fun <K, V> TreeMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val koin = startKoin { modules(networkModule) }.koin

        val symbol = args.arg("--symbol") ?: "BTCUSDT"
        val jdbcUrl = args.arg("--jdbc") ?: CandleCollectorConfig.DEFAULT.sqliteJdbcUrl

        val intervalStr = args.arg("--interval") ?: "1m"
        val topN = args.arg("--top")?.toIntOrNull() ?: 50   // 0 = all
        val showEmpty = (args.arg("--show-empty") ?: "false").equals("true", ignoreCase = true)

        val wsDepthSpeedMs = args.arg("--ws-depth-ms")?.toIntOrNull() ?: 100
        val wsSampleMs = args.arg("--ws-sample-ms")?.toLongOrNull() ?: 2_000L
        val verbose = (args.arg("--verbose") ?: "false").equals("true", ignoreCase = true)

        val endpoints: BinanceEndpoints = koin.get()

        println("============================================================")
        println(" Network Module Diagnostics")
        println("============================================================")
        println("Time (UTC): ${Instant.now()}")
        println("REST Base : ${endpoints.restBase}")
        println("WS Base   : ${endpoints.wsBase}")
        println("Symbol    : $symbol")
        println("DB        : $jdbcUrl")
        println("Interval  : $intervalStr   (for per-symbol DB coverage section)")
        println("TopN      : $topN          (0 = all)")
        println("ShowEmpty : $showEmpty")
        println("WS Depth  : ${wsDepthSpeedMs}ms")
        println("WS Sample : ${wsSampleMs}ms")
        println("Verbose   : $verbose")
        println("============================================================")
        println()

        val apiService: BinanceApiService = koin.get()
        val tickerService: TickerService = koin.get()
        val tradeService: TradeService = koin.get()
        val exchangeInfoService: ExchangeInfoService = koin.get()
        val orderBookService: BinanceOrderBookService = koin.get()
        val wsService: BinanceWebSocketService = koin.get()
        val httpClient: HttpClient = koin.get()

        val checks = mutableListOf<CheckResult>()

        // ---------- SPOT REST: basic connectivity ----------
        checks += runCheck("SPOT REST /ping") {
            val url = "${endpoints.restBase}/ping"
            httpClient.get(url) // 200 + empty body
            CheckResult("SPOT REST /ping", Status.PASS, 0, "GET /ping OK")
        }

        checks += runCheck("SPOT REST /time") {
            val url = "${endpoints.restBase}/time"
            val dto: ServerTimeDto = httpClient.get(url).body()
            val now = System.currentTimeMillis()
            val skewMs = abs(now - dto.serverTime)
            val status = if (skewMs <= 2_000) Status.PASS else Status.WARN
            CheckResult(
                "SPOT REST /time",
                status,
                0,
                "serverTime=${Instant.ofEpochMilli(dto.serverTime)} skewMs=$skewMs"
            )
        }

        // ---------- SPOT REST: your existing endpoints ----------
        checks += runCheck("SPOT REST /exchangeInfo") {
            val info = exchangeInfoService.getExchangeInfo()
            val count = info.symbols.size
            val hasSymbol = info.symbols.any { it.symbol == symbol }
            val status = when {
                count <= 0 -> Status.FAIL
                !hasSymbol -> Status.WARN
                else -> Status.PASS
            }
            CheckResult(
                "SPOT REST /exchangeInfo",
                status,
                0,
                "symbols=$count, contains($symbol)=$hasSymbol"
            )
        }

        checks += runCheck("SPOT REST /ticker/24hr") {
            val tickers = tickerService.getTickers24hr()
            val count = tickers.size
            val found = tickers.firstOrNull { it.symbol == symbol }
            val status = when {
                count <= 0 -> Status.FAIL
                found == null -> Status.WARN
                else -> Status.PASS
            }
            CheckResult(
                "SPOT REST /ticker/24hr",
                status,
                0,
                "tickers=$count, $symbol(last=${found?.lastPrice}, quoteVol=${found?.quoteVolume}, trades=${found?.tradeCount})"
            )
        }

        checks += runCheck("SPOT REST /klines (1m)") {
            val now = System.currentTimeMillis()
            val start = now - 30L * 60L * 1000L
            val klines = apiService.getKlines(
                symbol = symbol,
                interval = KlineInterval.ONE_MINUTE,
                limit = 60,
                startTime = start,
                endTime = null
            )
            val count = klines.size
            val monotonic = klines.zipWithNext().all { (a, b) -> b.openTime > a.openTime }
            val plausible = klines.all { it.closeTime >= it.openTime }
            val status = when {
                count == 0 -> Status.FAIL
                !monotonic || !plausible -> Status.WARN
                else -> Status.PASS
            }
            val first = klines.firstOrNull()?.openTime?.let { Instant.ofEpochMilli(it) }
            val last = klines.lastOrNull()?.openTime?.let { Instant.ofEpochMilli(it) }
            CheckResult(
                "SPOT REST /klines (1m)",
                status,
                0,
                "rows=$count, monotonic=$monotonic, range=$first → $last"
            )
        }

        checks += runCheck("SPOT REST /depth") {
            val ob = orderBookService.getDepth(symbol = symbol, limit = 20)
            val bids = ob.bids.size
            val asks = ob.asks.size
            val bestBid = ob.bids.maxByOrNull { it.price }?.price
            val bestAsk = ob.asks.minByOrNull { it.price }?.price
            val crossed = if (bestBid != null && bestAsk != null) bestBid >= bestAsk else false
            val status = when {
                bids == 0 || asks == 0 -> Status.FAIL
                crossed -> Status.WARN
                else -> Status.PASS
            }
            CheckResult(
                "SPOT REST /depth",
                status,
                0,
                "bids=$bids asks=$asks bestBid=$bestBid bestAsk=$bestAsk crossed=$crossed"
            )
        }

        checks += runCheck("SPOT REST /aggTrades") {
            val trades = tradeService.getAggTrades(symbol = symbol, fromId = null, limit = 25)
            val count = trades.size
            val status = if (count == 0) Status.FAIL else Status.PASS
            val firstTs = trades.firstOrNull()?.timestamp?.let { Instant.ofEpochMilli(it) }
            val lastTs = trades.lastOrNull()?.timestamp?.let { Instant.ofEpochMilli(it) }
            CheckResult("SPOT REST /aggTrades", status, 0, "rows=$count, ts=$firstTs → $lastTs")
        }

        // ---------- SPOT WS: decode checks ----------
        checks += runCheck("SPOT WS decode (kline_1m)") {
            val stream = "${symbol.lowercase()}@kline_1m"
            val first = withTimeoutOrNull(8_000L) { wsService.connect(listOf(stream)).first() }
            val data = first?.data
            val status = when {
                first == null -> Status.FAIL
                data !is WsKlineData -> Status.FAIL
                data.symbol.isBlank() -> Status.WARN
                else -> Status.PASS
            }
            val detail = if (data is WsKlineData) {
                "symbol=${data.symbol} closed=${data.kline.isClosed} t=${Instant.ofEpochMilli(data.kline.openTime)}"
            } else "got=${data?.javaClass?.simpleName ?: "null"}"
            CheckResult("SPOT WS decode (kline_1m)", status, 0, "$stream -> $detail")
        }

        checks += runCheck("SPOT WS decode (bookTicker)") {
            val stream = "${symbol.lowercase()}@bookTicker"
            val first = withTimeoutOrNull(8_000L) { wsService.connect(listOf(stream)).first() }
            val data = first?.data
            val status = when {
                first == null -> Status.FAIL
                data !is WsBookTickerData -> Status.FAIL
                else -> Status.PASS
            }
            val detail = if (data is WsBookTickerData) {
                "bid=${data.bestBidPrice} ask=${data.bestAskPrice}"
            } else "got=${data?.javaClass?.simpleName ?: "null"}"
            CheckResult("SPOT WS decode (bookTicker)", status, 0, "$stream -> $detail")
        }

        checks += runCheck("SPOT WS decode (depth ${wsDepthSpeedMs}ms)") {
            val stream = "${symbol.lowercase()}@depth@${wsDepthSpeedMs}ms"
            val first = withTimeoutOrNull(8_000L) { wsService.connect(listOf(stream)).first() }
            val data = first?.data
            val status = when {
                first == null -> Status.FAIL
                data !is WsDepthUpdateData -> Status.FAIL
                else -> Status.PASS
            }
            val detail = if (data is WsDepthUpdateData) {
                "bids=${data.bids.size} asks=${data.asks.size} U=${data.firstUpdateId} u=${data.finalUpdateId}"
            } else "got=${data?.javaClass?.simpleName ?: "null"}"
            CheckResult(
                "SPOT WS decode (depth ${wsDepthSpeedMs}ms)",
                status,
                0,
                "$stream -> $detail"
            )
        }

        // ---------- SPOT WS: rate / liveness sampling ----------
        checks += runCheck("SPOT WS rate (bookTicker ${wsSampleMs}ms)") {
            val stream = "${symbol.lowercase()}@bookTicker"
            val count = sampleCount(
                flow = wsService.connect(listOf(stream))
                    .mapNotNull { it.data as? WsBookTickerData },
                sampleMs = wsSampleMs
            )
            val eps = count.toDouble() / max(1.0, wsSampleMs.toDouble() / 1000.0)
            val status = when {
                count == 0 -> Status.FAIL
                eps < 1.0 -> Status.WARN
                else -> Status.PASS
            }
            CheckResult(
                "SPOT WS rate (bookTicker ${wsSampleMs}ms)",
                status,
                0,
                "events=$count (~${"%.1f".format(eps)}/s)"
            )
        }

        checks += runCheck("SPOT WS rate (depth ${wsDepthSpeedMs}ms, ${wsSampleMs}ms)") {
            val stream = "${symbol.lowercase()}@depth@${wsDepthSpeedMs}ms"
            val count = sampleCount(
                flow = wsService.connect(listOf(stream))
                    .mapNotNull { it.data as? WsDepthUpdateData },
                sampleMs = wsSampleMs
            )
            val eps = count.toDouble() / max(1.0, wsSampleMs.toDouble() / 1000.0)
            val status = when {
                count == 0 -> Status.FAIL
                eps < 1.0 -> Status.WARN
                else -> Status.PASS
            }
            CheckResult(
                "SPOT WS rate (depth ${wsDepthSpeedMs}ms, ${wsSampleMs}ms)",
                status,
                0,
                "events=$count (~${"%.1f".format(eps)}/s)"
            )
        }

        // ---------- SPOT WS + REST: Depth snapshot + incremental sync validation ----------
        checks += runCheck("SPOT L2 sync (snapshot + depth stream)") {
            coroutineScope {
                val depthStream = "${symbol.lowercase()}@depth@${wsDepthSpeedMs}ms"

                val ch = Channel<WsDepthUpdateData>(capacity = Channel.BUFFERED)

                val collectorJob = launch {
                    wsService.connect(listOf(depthStream))
                        .mapNotNull { it.data as? WsDepthUpdateData }
                        .collect { u -> ch.trySend(u) }
                }

                try {
                    // 1) Confirm stream is live and buffer a few early updates
                    val preBuffer = ArrayList<WsDepthUpdateData>(64)

                    val first = withTimeoutOrNull(2_000L) { ch.receive() }
                    if (first == null) {
                        return@coroutineScope CheckResult(
                            "SPOT L2 sync (snapshot + depth stream)",
                            Status.FAIL,
                            0,
                            "no depth updates received (stream=$depthStream)"
                        )
                    }
                    preBuffer.add(first)

                    val drainUntil = System.currentTimeMillis() + 250L
                    while (System.currentTimeMillis() < drainUntil) {
                        val u = ch.tryReceive().getOrNull() ?: break
                        preBuffer.add(u)
                    }

                    // 2) Snapshot AFTER WS is confirmed delivering
                    val snapshot = orderBookService.getDepth(symbol = symbol, limit = 1000)
                    val snapId = snapshot.lastUpdateId
                    val target = snapId + 1

                    var firstAfterSnap: WsDepthUpdateData? = null

                    fun consider(u: WsDepthUpdateData): WsDepthUpdateData? {
                        // ignore events older than snapshot
                        if (u.finalUpdateId <= snapId) return null

                        if (firstAfterSnap == null) firstAfterSnap = u

                        // bridge condition: U <= snapId+1 <= u
                        return if (u.firstUpdateId <= target && u.finalUpdateId >= target) u else null
                    }

                    // 3) Try find bridge in buffered events
                    var bridge: WsDepthUpdateData? = preBuffer.firstNotNullOfOrNull { consider(it) }

                    // 4) If not found, keep reading until found OR we prove we missed it
                    if (bridge == null) {
                        bridge = withTimeoutOrNull(8_000L) {
                            var b: WsDepthUpdateData? = null
                            while (b == null) {
                                val u = ch.receive()
                                b = consider(u)
                                if (b != null) break

                                // If we’ve advanced past target, we missed the bridge window.
                                if (u.firstUpdateId > target) break
                            }
                            b
                        }
                    }

                    if (bridge == null) {
                        val fa = firstAfterSnap
                        return@coroutineScope CheckResult(
                            "SPOT L2 sync (snapshot + depth stream)",
                            Status.FAIL,
                            0,
                            "no bridging update found (snapId=$snapId target=$target). " +
                                    "firstAfterSnap=${fa?.firstUpdateId}..${fa?.finalUpdateId} stream=$depthStream"
                        )
                    }

                    // 5) Apply snapshot + bridge
                    val book = LocalL2Book().apply { loadSnapshot(snapshot) }
                    book.applyBridge(bridge)

                    // 6) Apply buffered updates that came AFTER the chosen bridge
                    var okSeq = 0
                    val bufferedPostBridge = preBuffer.dropWhile { it !== bridge }.drop(1)
                    for (u in bufferedPostBridge) {
                        if (u.finalUpdateId <= book.lastUpdateId) continue
                        if (!book.applyNext(u)) break
                        okSeq++
                        if (okSeq >= 25) break
                    }

                    // 7) Continue applying live updates to validate sequencing
                    withTimeoutOrNull(4_000L) {
                        while (okSeq < 25) {
                            val u = ch.receive()
                            if (u.finalUpdateId <= book.lastUpdateId) continue
                            if (!book.applyNext(u)) break
                            okSeq++
                        }
                    }

                    val bestBid = book.bestBid()
                    val bestAsk = book.bestAsk()
                    val crossed = (bestBid != null && bestAsk != null && bestBid >= bestAsk)

                    // Compare to one bookTicker
                    val bt = withTimeoutOrNull(3_000L) {
                        wsService.connect(listOf("${symbol.lowercase()}@bookTicker"))
                            .mapNotNull { it.data as? WsBookTickerData }
                            .first()
                    }
                    val btBid = bt?.bestBidPrice?.toDoubleOrNull()
                    val btAsk = bt?.bestAskPrice?.toDoubleOrNull()

                    val relDiff = if (bestBid != null && btBid != null && btBid > 0)
                        abs(bestBid - btBid) / btBid
                    else null

                    val status = when {
                        okSeq < 3 -> Status.WARN
                        bestBid == null || bestAsk == null -> Status.FAIL
                        crossed -> Status.FAIL
                        relDiff != null && relDiff > 0.001 -> Status.WARN
                        else -> Status.PASS
                    }

                    return@coroutineScope CheckResult(
                        "SPOT L2 sync (snapshot + depth stream)",
                        status,
                        0,
                        "snapId=$snapId target=$target bridge=${bridge.firstUpdateId}..${bridge.finalUpdateId} " +
                                "seqOk=$okSeq bestBid=$bestBid bestAsk=$bestAsk btBid=$btBid btAsk=$btAsk " +
                                "relDiff=${relDiff?.let { "%.5f".format(it) }}"
                    )
                } finally {
                    collectorJob.cancel()
                    ch.close()
                }
            }
        }

        // ---------- USD-M Futures REST: connectivity ----------
        checks += runCheck("FUTURES REST /ping") {
            val url = "${BinanceEnvs.USD_M_FUTURES.restBase}/ping"
            httpClient.get(url)
            CheckResult("FUTURES REST /ping", Status.PASS, 0, "GET /fapi/v1/ping OK")
        }

        checks += runCheck("FUTURES REST /time") {
            val url = "${BinanceEnvs.USD_M_FUTURES.restBase}/time"
            val dto: ServerTimeDto = httpClient.get(url).body()
            val now = System.currentTimeMillis()
            val skewMs = abs(now - dto.serverTime)
            val status = if (skewMs <= 2_000) Status.PASS else Status.WARN
            CheckResult(
                "FUTURES REST /time",
                status,
                0,
                "serverTime=${Instant.ofEpochMilli(dto.serverTime)} skewMs=$skewMs"
            )
        }

        checks += runCheck("FUTURES REST /exchangeInfo") {
            val url = "${BinanceEnvs.USD_M_FUTURES.restBase}/exchangeInfo"
            val text = httpClient.get(url).bodyAsText()

            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val root = json.parseToJsonElement(text).jsonObject

            val symbolsCount = root["symbols"]?.jsonArray?.size ?: 0
            val status = if (symbolsCount > 0) Status.PASS else Status.WARN

            CheckResult(
                "FUTURES REST /exchangeInfo",
                status,
                0,
                "symbols=$symbolsCount keys=${root.keys.take(10)}..."
            )
        }

        checks += runCheck("FUTURES REST /depth") {
            val url = "${BinanceEnvs.USD_M_FUTURES.restBase}/depth?symbol=$symbol&limit=20"
            val ob: OrderBookDto = httpClient.get(url).body()
            val bids = ob.bids.size
            val asks = ob.asks.size
            val status = if (bids > 0 && asks > 0) Status.PASS else Status.FAIL
            CheckResult(
                "FUTURES REST /depth",
                status,
                0,
                "bids=$bids asks=$asks lastUpdateId=${ob.lastUpdateId}"
            )
        }

        // ---------- DB checks ----------
        checks += runCheck("DB open + candles table") {
            DriverManager.getConnection(jdbcUrl).use { conn ->
                val tableExists = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='candles'"
                ).use { ps ->
                    ps.executeQuery().use { rs -> rs.next() }
                }
                val status = if (tableExists) Status.PASS else Status.WARN
                CheckResult("DB open + candles table", status, 0, "candlesTableExists=$tableExists")
            }
        }

        checks += runCheck("DB row counts + recency") {
            DriverManager.getConnection(jdbcUrl).use { conn ->
                val total = conn.scalarLong("SELECT COUNT(*) FROM candles") ?: 0L
                val bySymbol = conn.scalarLong(
                    "SELECT COUNT(*) FROM candles WHERE symbol=? AND interval=?",
                    symbol, intervalStr
                ) ?: 0L

                val maxOt = conn.scalarLong(
                    "SELECT MAX(open_time) FROM candles WHERE symbol=? AND interval=?",
                    symbol, intervalStr
                )

                val now = System.currentTimeMillis()
                val ageMin = maxOt?.let { abs(now - it) / 60_000L }

                val status = when {
                    total == 0L -> Status.WARN
                    bySymbol == 0L -> Status.WARN
                    (ageMin != null && ageMin > 180) -> Status.WARN
                    else -> Status.PASS
                }

                val maxStr = maxOt?.let { Instant.ofEpochMilli(it) } ?: "null"
                CheckResult(
                    "DB row counts + recency",
                    status,
                    0,
                    "total=$total, $symbol($intervalStr=$bySymbol max=$maxStr ageMin=$ageMin)"
                )
            }
        }

        checks += runCheck("DB gap check (last 500 bars)") {
            val intervalMs = try {
                Intervals.toMs(intervalStr)
            } catch (_: Exception) {
                60_000L
            }
            DriverManager.getConnection(jdbcUrl).use { conn ->
                val times = conn.queryOpenTimes(symbol, intervalStr, 500)
                if (times.size < 3) {
                    return@runCheck CheckResult(
                        "DB gap check (last 500 bars)",
                        Status.WARN,
                        0,
                        "not enough rows to check (rows=${times.size})"
                    )
                }

                // times are DESC
                var gaps = 0
                var maxGapBars = 0L
                for (i in 0 until times.size - 1) {
                    val a = times[i]
                    val b = times[i + 1]
                    val diff = a - b
                    if (diff != intervalMs) {
                        gaps++
                        val bars = diff / intervalMs
                        maxGapBars = max(maxGapBars, bars)
                    }
                }

                val status = when {
                    gaps == 0 -> Status.PASS
                    gaps <= 3 -> Status.WARN
                    else -> Status.FAIL
                }

                CheckResult(
                    "DB gap check (last 500 bars)",
                    status,
                    0,
                    "rows=${times.size} gaps=$gaps maxGapBars=$maxGapBars (intervalMs=$intervalMs)"
                )
            }
        }

        checks += runCheck("DB completeness (6mo target)") {
            val intervalMs = try {
                Intervals.toMs(intervalStr)
            } catch (_: Exception) {
                60_000L
            }
            val backfillDays = CandleCollectorConfig.DEFAULT.backfillDays
            val now = System.currentTimeMillis()
            val desiredStart = floorToInterval(
                now - backfillDays * 24L * 60L * 60L * 1000L,
                intervalMs
            )

            DriverManager.getConnection(jdbcUrl).use { conn ->
                val rows = conn.querySymbolCoverageOnlyWithRows(intervalStr, now)
                if (rows.isEmpty()) {
                    return@runCheck CheckResult(
                        "DB completeness (6mo target)",
                        Status.WARN,
                        0,
                        "no rows for interval=$intervalStr"
                    )
                }

                var pass = 0
                var warn = 0
                var fail = 0
                val offenders = ArrayList<String>()

                rows.forEach { r ->
                    val minOt = r.minOpenTime
                    val maxOt = r.maxOpenTime
                    if (minOt == null || maxOt == null) {
                        warn++
                        offenders += "${r.symbol}(missing=min/max)"
                        return@forEach
                    }

                    val expectedSpan = ((maxOt - minOt) / intervalMs) + 1
                    val missingSpan = (expectedSpan - r.rows).coerceAtLeast(0)
                    val missingFromTarget = if (minOt > desiredStart) {
                        ((minOt - desiredStart) / intervalMs)
                    } else {
                        0L
                    }

                    when {
                        missingSpan > 0 -> {
                            fail++
                            if (offenders.size < 8) {
                                offenders += "${r.symbol}(gaps=${missingSpan})"
                            }
                        }
                        missingFromTarget > 0 -> {
                            warn++
                            if (offenders.size < 8) {
                                offenders += "${r.symbol}(lateStart=${missingFromTarget})"
                            }
                        }
                        else -> pass++
                    }
                }

                val status = when {
                    fail > 0 -> Status.FAIL
                    warn > 0 -> Status.WARN
                    else -> Status.PASS
                }

                val detail = buildString {
                    append("symbols=${rows.size} pass=$pass warn=$warn fail=$fail ")
                    append("| targetStart=${Instant.ofEpochMilli(desiredStart)}")
                    if (offenders.isNotEmpty()) {
                        append(" | offenders=")
                        append(offenders.joinToString(","))
                    }
                }

                CheckResult("DB completeness (6mo target)", status, 0, detail.truncate(260))
            }
        }

        checks += runCheck("DB per-symbol min/max ($intervalStr)") {
            DriverManager.getConnection(jdbcUrl).use { conn ->
                val now = System.currentTimeMillis()
                val rows = if (showEmpty) {
                    conn.querySymbolCoverageAllSymbols(intervalStr, now)
                } else {
                    conn.querySymbolCoverageOnlyWithRows(intervalStr, now)
                }

                val sorted = rows
                    .sortedWith(compareByDescending<SymbolCoverageRow> { it.rows }.thenBy { it.symbol })

                printSymbolCoverageTable(sorted, topN)

                val status = if (sorted.isEmpty()) Status.WARN else Status.PASS
                CheckResult(
                    "DB per-symbol min/max ($intervalStr)",
                    status,
                    0,
                    "symbols=${sorted.size} (printed ${if (topN == 0) "all" else "top $topN"})"
                )
            }
        }

        // ---------- Print report ----------
        printResults(checks, verbose)

        val fail = checks.count { it.status == Status.FAIL }
        val warn = checks.count { it.status == Status.WARN }
        val pass = checks.count { it.status == Status.PASS }

        println()
        println("============================================================")
        println(" Summary: PASS=$pass  WARN=$warn  FAIL=$fail")
        println("============================================================")

        if (fail > 0) {
            println()
            println("Tips for FAIL cases:")
            println("- If REST fails: verify internet/DNS and that Binance isn’t blocked on your network.")
            println("- If WS fails: verify wsBase reachable; confirm combined-stream envelope decode.")
            println("- If L2 sync fails: depth stream sequencing is broken or snapshot/bridge logic needs attention.")
            println("- If DB warns: run the candle collector for a bit, then re-run this report.")
        }

        println()
        println("✅ What to build next (MVP) — implemented below")
        println("- ArchiveDownloader: daily ZIP bundles with bookDepth + aggTrades that prove resilient to column-order changes.")
        println("- MarketStateBuilder: fixed cadence ticks merging bookTicker, depth diffs, trades, and rolling vol into one atomic `MarketState` per symbol.")
        println("- Dedicated SQLite store for real-time market snapshots so the candle DB remains untouched by latency-sensitive consumers.")
        println("- Replayer: stream `MarketState` into the backtester so every strategy uses the same trusted data.")
        println("- Conservative Fill Simulator: crude execution proxy that runs off the live feeds instead of the 1m candle snapshot.")
        println("- Keep these pieces in the network layer so any future strategy can reuse the shared feed instead of doubling plumbing.")

        stopKoin()
    }

    // ---------------- helpers ----------------

    private suspend fun runCheck(name: String, block: suspend () -> CheckResult): CheckResult {
        var result: CheckResult? = null
        val ms = measureTimeMillis {
            result = try {
                block()
            } catch (t: Throwable) {
                CheckResult(
                    name = name,
                    status = Status.FAIL,
                    ms = 0,
                    detail = "exception=${t::class.simpleName}: ${t.message}".truncate(220),
                    error = t
                )
            }
        }
        return result!!.copy(ms = ms)
    }

    private fun printResults(checks: List<CheckResult>, verbose: Boolean) {
        val maxName = checks.maxOfOrNull { it.name.length } ?: 10
        val fmt = "%-${maxName}s  %5s  %6d ms  %s"

        println(String.format("%-${maxName}s  %5s  %6s     %s", "CHECK", "STAT", "TIME", "DETAIL"))
        println("-".repeat(88))

        checks.forEach { r ->
            println(String.format(fmt, r.name, r.status, r.ms, r.detail.truncate(260)))
            if (verbose && r.error != null) r.error.printStackTrace()
        }
    }

    private fun printSymbolCoverageTable(rows: List<SymbolCoverageRow>, topN: Int) {
        println()
        println("------------------------------------------------------------")
        println(" DB Candle Coverage (interval=${rows.firstOrNull()?.interval ?: "?"})")
        println("------------------------------------------------------------")

        val toPrint = if (topN <= 0) rows else rows.take(topN)

        val header = String.format(
            "%-12s %10s  %-24s  %-24s  %8s",
            "SYMBOL", "ROWS", "MIN(open_time)", "MAX(open_time)", "AGE_MIN"
        )
        println(header)
        println("-".repeat(header.length))

        toPrint.forEach { r ->
            val minStr = r.minOpenTime?.let { Instant.ofEpochMilli(it).toString() } ?: "null"
            val maxStr = r.maxOpenTime?.let { Instant.ofEpochMilli(it).toString() } ?: "null"
            val ageStr = r.ageMin?.toString() ?: "null"
            println(
                String.format(
                    "%-12s %10d  %-24s  %-24s  %8s",
                    r.symbol, r.rows, minStr, maxStr, ageStr
                )
            )
        }

        if (topN > 0 && rows.size > topN) {
            println("... (${rows.size - topN} more; run with --top 0 to print all)")
        }
        println("------------------------------------------------------------")
        println()
    }

    private fun Array<String>.arg(key: String): String? {
        val idx = indexOf(key)
        return if (idx >= 0 && idx + 1 < size) get(idx + 1) else null
    }

    private fun String.truncate(maxLen: Int): String =
        if (length <= maxLen) this else take(maxLen) + "…"

    private fun floorToInterval(tsMs: Long, intervalMs: Long): Long {
        return (tsMs / intervalMs) * intervalMs
    }

    private suspend fun <T> sampleCount(flow: Flow<T>, sampleMs: Long): Int = coroutineScope {
        var n = 0
        val job = launch { flow.collect { n++ } }
        delay(sampleMs)
        job.cancel()
        job.join()
        n
    }

    private fun java.sql.Connection.scalarLong(sql: String, vararg params: Any?): Long? {
        this.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    val v = rs.getObject(1)
                    if (v is Number) v.toLong() else null
                } else null
            }
        }
    }

    private fun java.sql.Connection.queryOpenTimes(
        symbol: String,
        interval: String,
        limit: Int
    ): List<Long> {
        val sql = """
            SELECT open_time
            FROM candles
            WHERE symbol = ? AND interval = ?
            ORDER BY open_time DESC
            LIMIT ?
        """.trimIndent()

        this.prepareStatement(sql).use { ps ->
            ps.setString(1, symbol)
            ps.setString(2, interval)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<Long>(limit)
                while (rs.next()) out.add(rs.getLong(1))
                return out
            }
        }
    }

    private fun java.sql.Connection.querySymbolCoverageOnlyWithRows(
        interval: String,
        nowMs: Long
    ): List<SymbolCoverageRow> {
        val sql = """
            SELECT symbol,
                   interval,
                   COUNT(*) AS rows,
                   MIN(open_time) AS min_ot,
                   MAX(open_time) AS max_ot
            FROM candles
            WHERE interval = ?
            GROUP BY symbol, interval
        """.trimIndent()

        this.prepareStatement(sql).use { ps ->
            ps.setString(1, interval)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SymbolCoverageRow>(256)
                while (rs.next()) {
                    val sym = rs.getString("symbol")
                    val intv = rs.getString("interval")
                    val rows = rs.getLong("rows")
                    val minOt = rs.getLong("min_ot").let { if (rs.wasNull()) null else it }
                    val maxOt = rs.getLong("max_ot").let { if (rs.wasNull()) null else it }
                    val ageMin = maxOt?.let { abs(nowMs - it) / 60_000L }
                    out.add(SymbolCoverageRow(sym, intv, rows, minOt, maxOt, ageMin))
                }
                return out
            }
        }
    }

    private fun java.sql.Connection.querySymbolCoverageAllSymbols(
        interval: String,
        nowMs: Long
    ): List<SymbolCoverageRow> {
        val sql = """
            WITH symbols AS (
              SELECT DISTINCT symbol FROM candles
            ),
            stats AS (
              SELECT symbol,
                     interval,
                     COUNT(*) AS rows,
                     MIN(open_time) AS min_ot,
                     MAX(open_time) AS max_ot
              FROM candles
              WHERE interval = ?
              GROUP BY symbol, interval
            )
            SELECT s.symbol AS symbol,
                   ? AS interval,
                   COALESCE(st.rows, 0) AS rows,
                   st.min_ot AS min_ot,
                   st.max_ot AS max_ot
            FROM symbols s
            LEFT JOIN stats st ON st.symbol = s.symbol
            ORDER BY rows DESC, symbol ASC
        """.trimIndent()

        this.prepareStatement(sql).use { ps ->
            ps.setString(1, interval)
            ps.setString(2, interval)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SymbolCoverageRow>(256)
                while (rs.next()) {
                    val sym = rs.getString("symbol")
                    val intv = rs.getString("interval")
                    val rows = rs.getLong("rows")
                    val minOtObj = rs.getObject("min_ot")
                    val maxOtObj = rs.getObject("max_ot")
                    val minOt = (minOtObj as? Number)?.toLong()
                    val maxOt = (maxOtObj as? Number)?.toLong()
                    val ageMin = maxOt?.let { abs(nowMs - it) / 60_000L }
                    out.add(SymbolCoverageRow(sym, intv, rows, minOt, maxOt, ageMin))
                }
                return out
            }
        }
    }
}
