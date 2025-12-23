package com.example.orderbookscalper

import com.example.network.AggTrade
import com.example.network.BinanceMarketDataService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.model.OrderBookDepth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.isNotEmpty
import kotlin.math.max
import kotlin.math.min

class OrderBookScalperEngine(
    private val config: ScalperConfig,
    private val orderBookService: BinanceOrderBookService = BinanceOrderBookService.create(),
    private val marketDataService: BinanceMarketDataService = BinanceMarketDataService()
) {
    private val requestSemaphore = Semaphore(config.maxConcurrentRequests)
    private val globalMutex = Mutex()
    private val globalState = GlobalState(
        equity = config.startingEquity,
        peakEquity = config.startingEquity,
        openPositions = 0,
        killSwitch = false
    )
    private val stats = ScalperStats()
    private val csvLogger = if (config.csvEnabled) CsvLogger(config.csvDir) else null
    private val symbolStates = ConcurrentHashMap<String, SymbolState>()
    private val symbolJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val activeSymbols = LinkedHashSet<String>()

    suspend fun start() = supervisorScope {
        check(config.quoteAsset.uppercase() == config.quoteAsset) { "quoteAsset must be uppercase (USDT, etc.)" }
        logGlobal("USDT quote target enforced (quoteAsset=${config.quoteAsset})")
        csvLogger?.let {
            it.writeRunInfo(config)
            logGlobal("CSV logging enabled dir=${config.csvDir} runId=${it.runId}")
        }
        val selectionJob = launch(Dispatchers.IO) { selectionLoop(this) }
        val statsJob = launch(Dispatchers.IO) { statsLoop() }
        if (config.runMs > 0) {
            delay(config.runMs)
            logGlobal("Run window complete. Stopping.")
            selectionJob.cancel()
            statsJob.cancel()
            val symbolJobSnapshot = symbolJobs.values.toList()
            symbolJobSnapshot.forEach { it.cancel() }
            selectionJob.join()
            statsJob.join()
            symbolJobSnapshot.joinAll()
            return@supervisorScope
        } else {
            while (isActive) delay(1_000L)
        }
    }

    private suspend fun selectionLoop(scope: kotlinx.coroutines.CoroutineScope) {
        if (config.fixedSymbols.isNotEmpty()) {
            updateActiveSymbols(scope, config.fixedSymbols.map { it.uppercase() }.toSet())
            return
        }
        while (scope.isActive) {
            try {
                val selected = selectActiveSymbols()
                updateActiveSymbols(scope, selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logGlobal("Selection error: ${e.message}")
            }
            delay(config.selectionIntervalMs)
        }
    }

    private suspend fun statsLoop() {
        while (true) {
            delay(config.statsIntervalMs)
            logSummary()
        }
    }

    private suspend fun selectActiveSymbols(): Set<String> {
        val tickers = requestSemaphore.withPermit { marketDataService.get24HrTickers() }
        val filtered = tickers.filter { ticker ->
            isEligibleSymbol(ticker.symbol) &&
                    ticker.quoteVolume >= config.minQuoteVolume &&
                    ticker.tradeCount >= config.minTradeCount &&
                    ticker.lastPrice >= config.minLastPrice
        }
        val sortedByVolume = filtered.sortedByDescending { it.quoteVolume }
        val topByVolume = if (config.maxScanSymbols > 0) {
            sortedByVolume.take(config.maxScanSymbols)
        } else {
            sortedByVolume
        }

        val candidates = coroutineScope {
            topByVolume.map { ticker ->
                async(Dispatchers.IO) {
                    val depth = safeDepth(ticker.symbol) ?: return@async null
                    val snapshot = buildSnapshot(depth, System.currentTimeMillis()) ?: return@async null
                    if (!spreadInBand(snapshot.spreadPct)) return@async null
                    val depthNotional = snapshot.bidDepthNotional + snapshot.askDepthNotional
                    if (depthNotional < config.minDepthNotional) return@async null
                    SymbolCandidate(
                        symbol = ticker.symbol,
                        quoteVolume = ticker.quoteVolume,
                        spreadPct = snapshot.spreadPct,
                        depthNotional = depthNotional
                    )
                }
            }.awaitAll()
        }

        val selected = candidates.filterNotNull()
            .sortedByDescending { it.quoteVolume }
            .take(config.maxActiveSymbols)
            .map { it.symbol }
            .toSet()

        logGlobal(
            "Selection: scanned=${topByVolume.size} eligible=${selected.size} " +
                "minVol=${config.minQuoteVolume} minTrades=${config.minTradeCount} maxScan=${if (config.maxScanSymbols > 0) config.maxScanSymbols else "ALL"}"
        )
        return selected
    }

    private fun updateActiveSymbols(scope: kotlinx.coroutines.CoroutineScope, nextSymbols: Set<String>) {
        val normalized = nextSymbols.map { it.uppercase() }.toSet() - config.excludedSymbols
        val toAdd = normalized - activeSymbols
        val toRemove = activeSymbols - normalized

        toRemove.forEach { symbol ->
            symbolJobs.remove(symbol)?.cancel()
            activeSymbols.remove(symbol)
        }

        toAdd.forEach { symbol ->
            val job = scope.launch(Dispatchers.IO) { runSymbol(symbol) }
            symbolJobs[symbol] = job
            activeSymbols.add(symbol)
        }

        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            logGlobal("Active symbols: ${activeSymbols.joinToString(",")}")
        }
    }

    private suspend fun runSymbol(symbol: String) {
        val state = symbolStates.computeIfAbsent(symbol) { SymbolState(symbol) }
        while (true) {
            val nowMs = System.currentTimeMillis()
            val depth = safeDepth(symbol)
            if (depth == null) {
                delay(config.pollIntervalMs)
                continue
            }

            val snapshot = buildSnapshot(depth, nowMs)
            if (snapshot == null) {
                delay(config.pollIntervalMs)
                continue
            }

            val trades = safeAggTrades(symbol, state.lastAggTradeId)
            if (trades.isNotEmpty()) {
                state.lastAggTradeId = trades.maxOf { it.tradeId }
            }

            val gapMs = if (state.lastUpdateMs == 0L) 0L else nowMs - state.lastUpdateMs
            val dataStale = gapMs > config.maxStaleMs

            updateStateWindows(state, snapshot, trades, nowMs)
            val flowSnapshot = computeFlowSnapshot(state.tradeWindow)
            val volProxyPct = computeVolProxyPct(state.midSamples)

            handlePositionExit(state, snapshot, nowMs)
            val killSwitch = globalMutex.withLock { globalState.killSwitch }
            if (killSwitch) {
                state.pendingOrder?.let { cancelPending(state, "kill-switch", snapshot, flowSnapshot, volProxyPct, nowMs) }
                delay(config.pollIntervalMs)
                continue
            }

            val signal = computeSignal(snapshot, flowSnapshot, volProxyPct)
            updateSignalStreaks(state, signal)
            handlePendingOrder(state, snapshot, trades, signal, flowSnapshot, volProxyPct, nowMs)

            if (dataStale && config.verbose) {
                logSymbol(state.symbol, "Data stale gapMs=$gapMs")
            }
            if (dataStale) {
                state.pendingOrder?.let { cancelPending(state, "stale", snapshot, flowSnapshot, volProxyPct, nowMs) }
            }

            if (!dataStale && state.position == null && state.pendingOrder == null && nowMs >= state.cooldownUntil) {
                maybePlaceOrder(state, snapshot, signal, nowMs, flowSnapshot, volProxyPct)
            }

            state.lastUpdateMs = nowMs
            delay(config.pollIntervalMs)
        }
    }

    private fun computeSignal(
        snapshot: BookSnapshot,
        flowSnapshot: FlowSnapshot,
        volProxyPct: Double
    ): SignalStatus {
        val spreadOk = spreadInBand(snapshot.spreadPct)
        val microEdge = snapshot.microEdge ?: return SignalStatus(false, false)
        val volOk = volProxyPct >= config.minVolProxyPct

        val flowOk =
            flowSnapshot.tradeCount >= config.minFlowTrades &&
                    flowSnapshot.totalNotional >= config.minFlowNotional

        val longSignal = spreadOk &&
                volOk &&
                flowOk &&
                snapshot.imbalance >= config.imbalanceThresh &&
                microEdge >= config.microEdgeThresh &&
                flowSnapshot.imbalance >= config.flowImbalanceThresh

        val shortSignal = spreadOk &&
                volOk &&
                flowOk &&
                snapshot.imbalance <= -config.imbalanceThresh &&
                microEdge <= -config.microEdgeThresh &&
                flowSnapshot.imbalance <= -config.flowImbalanceThresh

        return SignalStatus(longSignal, shortSignal)
    }


    private suspend fun maybePlaceOrder(
        state: SymbolState,
        snapshot: BookSnapshot,
        signal: SignalStatus,
        nowMs: Long,
        flowSnapshot: FlowSnapshot,
        volProxyPct: Double
    ) {
        val side = when {
            signal.long -> Side.LONG
            signal.short -> Side.SHORT
            else -> return
        }

        val streakOk = when (side) {
            Side.LONG -> state.longSignalStreak >= config.minSignalConsecutive
            Side.SHORT -> state.shortSignalStreak >= config.minSignalConsecutive
        }
        if (!streakOk) return


        val allowEntry = globalMutex.withLock {
            !globalState.killSwitch && globalState.openPositions < config.maxOpenPositions
        }
        if (!allowEntry) return

        val notional = globalMutex.withLock {
            min(config.maxSymbolNotional, globalState.equity * config.perSymbolMaxNotionalPct)
        }
        if (notional <= 0.0 || snapshot.mid <= 0.0) return

        val entryPrice = resolveEntryPrice(side, snapshot)
        val qty = notional / snapshot.mid
        val entryOffset = computeEntryOffset(snapshot.spread)
        if (qty <= 0.0) return

        state.pendingOrder = PendingOrder(
            side = side,
            price = entryPrice,
            quantity = qty,
            placedAt = nowMs,
            ttlMs = config.entryTtlMs,
            entrySpread = snapshot.spread,
            entryOffset = entryOffset
        )
        globalMutex.withLock { stats.ordersPlaced += 1 }
        csvLogger?.logOrderEvent(
            timestampMs = nowMs,
            symbol = state.symbol,
            event = "PLACE",
            side = side,
            price = entryPrice,
            quantity = qty,
            filledQuantity = 0.0,
            orderAgeMs = 0L,
            reason = null,
            snapshot = snapshot,
            flowSnapshot = flowSnapshot,
            volProxyPct = volProxyPct
        )
        val entryOffsetPct = if (snapshot.spread > 0.0) entryOffset / snapshot.spread else 0.0
        logSymbol(
            state.symbol,
            "ENTRY_PENDING side=$side price=${fmt(entryPrice)} qty=${fmt(qty)} " +
                "entryOffset=${fmt(entryOffset)} entryOffsetPct=${fmtPct(entryOffsetPct)} " +
                "spreadPct=${fmtPct(snapshot.spreadPct)} imb=${fmt(snapshot.imbalance)} " +
                "microEdge=${fmt(snapshot.microEdge)} flowImb=${fmt(flowSnapshot.imbalance)} volProxy=${fmtPct(volProxyPct)}"
        )
    }

    private suspend fun handlePendingOrder(
        state: SymbolState,
        snapshot: BookSnapshot,
        trades: List<AggTrade>,
        signal: SignalStatus,
        flowSnapshot: FlowSnapshot,
        volProxyPct: Double,
        nowMs: Long
    ) {
        val pending = state.pendingOrder ?: return
        val expired = nowMs - pending.placedAt >= pending.ttlMs
        val spreadOk = spreadInBand(snapshot.spreadPct)
        val signalOk = if (!config.cancelOnSignalFlip) {
            true
        } else {
            when (pending.side) {
                Side.LONG -> signal.long
                Side.SHORT -> signal.short
            }
        }

        if (expired) {
            cancelPending(state, "ttl", snapshot, flowSnapshot, volProxyPct, nowMs)
            return
        }
        if (!spreadOk) {
            cancelPending(state, "spread", snapshot, flowSnapshot, volProxyPct, nowMs)
            return
        }
        if (!signalOk) {
            cancelPending(state, "signal", snapshot, flowSnapshot, volProxyPct, nowMs)
            return
        }

        val priceEps = pending.price * 1e-7
        val fillQty = trades.asSequence()
            .filter { it.timestamp >= pending.placedAt }
            .sumOf { trade ->
                if (pending.side == Side.LONG) {
                    if (trade.isBuyerMaker && trade.price <= pending.price + priceEps) trade.quantity else 0.0
                } else {
                    if (!trade.isBuyerMaker && trade.price >= pending.price - priceEps) trade.quantity else 0.0
                }
            }
        if (fillQty <= 0.0) return

        pending.filledQuantity += fillQty
        if (pending.filledQuantity < pending.quantity) return

        val allowed = globalMutex.withLock {
            if (globalState.killSwitch) return@withLock false
            if (globalState.openPositions >= config.maxOpenPositions) return@withLock false
            globalState.openPositions += 1
            true
        }
        if (!allowed) {
            cancelPending(state, "limit", snapshot, flowSnapshot, volProxyPct, nowMs)
            return
        }

        val entrySpread = if (pending.entrySpread > 0.0) pending.entrySpread else snapshot.spread
        val tp = if (pending.side == Side.LONG) {
            pending.price + config.tpMult * entrySpread
        } else {
            pending.price - config.tpMult * entrySpread
        }
        val sl = if (pending.side == Side.LONG) {
            pending.price - config.slMult * entrySpread
        } else {
            pending.price + config.slMult * entrySpread
        }
        val trailArm = config.trailArmMult * entrySpread
        val position = Position(
            side = pending.side,
            entryPrice = pending.price,
            entryTime = nowMs,
            quantity = pending.quantity,
            entrySpread = entrySpread,
            tp = tp,
            sl = sl,
            trailArm = trailArm,
            trailMult = config.trailMult,
            entryMid = snapshot.mid,
            maxHoldMs = config.maxHoldMs,
            entryImbalance = snapshot.imbalance,
            entryMicroPrice = snapshot.microPrice,
            entryMicroEdge = snapshot.microEdge,
            entryFlowImbalance = flowSnapshot.imbalance,
            entryVolProxyPct = volProxyPct,
            entrySpreadPct = snapshot.spreadPct,
            entryBidDepthNotional = snapshot.bidDepthNotional,
            entryAskDepthNotional = snapshot.askDepthNotional,
            entryOffset = pending.entryOffset
        )
        val fillTimeMs = nowMs - pending.placedAt
        state.position = position
        state.pendingOrder = null
        globalMutex.withLock {
            stats.ordersFilled += 1
            stats.fillTimeSumMs += fillTimeMs
            stats.fillTimeSamples += 1
        }
        csvLogger?.logOrderEvent(
            timestampMs = nowMs,
            symbol = state.symbol,
            event = "FILL",
            side = position.side,
            price = position.entryPrice,
            quantity = position.quantity,
            filledQuantity = position.quantity,
            orderAgeMs = fillTimeMs,
            reason = null,
            snapshot = snapshot,
            flowSnapshot = flowSnapshot,
            volProxyPct = volProxyPct
        )

        logSymbol(
            state.symbol,
            "ENTRY_FILLED side=${position.side} price=${fmt(position.entryPrice)} " +
                "qty=${fmt(position.quantity)} fillMs=$fillTimeMs"
        )
    }

    private suspend fun handlePositionExit(state: SymbolState, snapshot: BookSnapshot, nowMs: Long): Boolean {
        val position = state.position ?: return false
        val exitResolution = resolveExitPrice(position.side, snapshot, state.lastTradePrice)
        val exitPrice = exitResolution.price

        updateAdverseMoves(position, snapshot, nowMs)

        if (!position.trailArmed) {
            val move = if (position.side == Side.LONG) {
                exitPrice - position.entryPrice
            } else {
                position.entryPrice - exitPrice
            }
            if (move >= position.trailArm) {
                position.trailArmed = true
                if (position.side == Side.LONG) position.peakPrice = exitPrice else position.troughPrice = exitPrice
            }
        } else {
            if (position.side == Side.LONG) {
                position.peakPrice = max(position.peakPrice, exitPrice)
            } else {
                position.troughPrice = min(position.troughPrice, exitPrice)
            }
        }

        val trailStopHit = if (position.trailArmed) {
            if (position.side == Side.LONG) {
                exitPrice <= position.peakPrice - position.trailMult * position.entrySpread
            } else {
                exitPrice >= position.troughPrice + position.trailMult * position.entrySpread
            }
        } else {
            false
        }

        val tpHit = if (position.side == Side.LONG) exitPrice >= position.tp else exitPrice <= position.tp
        val slHit = if (position.side == Side.LONG) exitPrice <= position.sl else exitPrice >= position.sl
        val hzHit = nowMs - position.entryTime >= position.maxHoldMs

        val exitReason = when {
            slHit -> "SL"
            tpHit -> "TP"
            trailStopHit -> "TR"
            hzHit -> "HZ"
            else -> null
        }
        if (exitReason == null) return false

        val grossPct = if (position.side == Side.LONG) {
            (exitPrice - position.entryPrice) / position.entryPrice
        } else {
            (position.entryPrice - exitPrice) / position.entryPrice
        }
        val netPct = grossPct - (config.feePctPerSide * 2.0) - config.exitSlippagePct
        val pnl = position.quantity * position.entryPrice * netPct

        val equityAfter = globalMutex.withLock {
            stats.trades += 1
            stats.grossPnl += position.quantity * position.entryPrice * grossPct
            stats.netPnl += pnl
            if (netPct > 0.0) stats.wins += 1 else stats.losses += 1
            position.adverse1sPct?.let {
                stats.adverse1sSum += it
                stats.adverse1sCount += 1
            }
            position.adverse5sPct?.let {
                stats.adverse5sSum += it
                stats.adverse5sCount += 1
            }

            globalState.equity += pnl
            globalState.openPositions = max(0, globalState.openPositions - 1)
            if (globalState.equity > globalState.peakEquity) {
                globalState.peakEquity = globalState.equity
            }
            val drawdownPct = if (globalState.peakEquity > 0.0) {
                (globalState.peakEquity - globalState.equity) / globalState.peakEquity
            } else {
                0.0
            }
            if (drawdownPct >= config.maxDrawdownPct) {
                globalState.killSwitch = true
            }
            globalState.equity
        }

        if (exitReason == "SL") {
            state.cooldownUntil = nowMs + config.cooldownMs
        }

        val adverse1s = position.adverse1sPct?.let { fmtPct(it) } ?: "n/a"
        val adverse5s = position.adverse5sPct?.let { fmtPct(it) } ?: "n/a"
        csvLogger?.logTradeExit(
            timestampMs = nowMs,
            symbol = state.symbol,
            side = position.side,
            entryTimeMs = position.entryTime,
            exitTimeMs = nowMs,
            entryPrice = position.entryPrice,
            exitPrice = exitPrice,
            quantity = position.quantity,
            tp = position.tp,
            sl = position.sl,
            trailArm = position.trailArm,
            trailMult = position.trailMult,
            entrySpread = position.entrySpread,
            entryOffset = position.entryOffset,
            entrySpreadPct = position.entrySpreadPct,
            entryMid = position.entryMid,
            exitMid = snapshot.mid,
            exitBasis = exitResolution.basis,
            lastTradePrice = state.lastTradePrice,
            entryImbalance = position.entryImbalance,
            entryMicroPrice = position.entryMicroPrice,
            entryMicroEdge = position.entryMicroEdge,
            entryFlowImbalance = position.entryFlowImbalance,
            entryVolProxyPct = position.entryVolProxyPct,
            entryBidDepthNotional = position.entryBidDepthNotional,
            entryAskDepthNotional = position.entryAskDepthNotional,
            grossPct = grossPct,
            netPct = netPct,
            pnl = pnl,
            reason = exitReason,
            adverse1sPct = position.adverse1sPct,
            adverse5sPct = position.adverse5sPct
        )
        logSymbol(
            state.symbol,
            "EXIT side=${position.side} reason=$exitReason exit=${fmt(exitPrice)} basis=${exitResolution.basis} " +
                "grossPct=${fmtPct(grossPct)} netPct=${fmtPct(netPct)} pnl=${fmt(pnl)} " +
                "equity=${fmt(equityAfter)} adverse1s=$adverse1s adverse5s=$adverse5s"
        )

        state.position = null
        return true
    }

    private fun updateAdverseMoves(position: Position, snapshot: BookSnapshot, nowMs: Long) {
        val elapsed = nowMs - position.entryTime
        if (position.entryMid <= 0.0) return

        if (position.adverse1sPct == null && elapsed >= 1_000L) {
            position.adverse1sPct = adverseMovePct(position, snapshot.mid)
        }
        if (position.adverse5sPct == null && elapsed >= 5_000L) {
            position.adverse5sPct = adverseMovePct(position, snapshot.mid)
        }
    }

    private fun adverseMovePct(position: Position, currentMid: Double): Double {
        val movePct = (currentMid - position.entryMid) / position.entryMid
        return if (position.side == Side.LONG) {
            max(0.0, -movePct)
        } else {
            max(0.0, movePct)
        }
    }

    private suspend fun cancelPending(
        state: SymbolState,
        reason: String,
        snapshot: BookSnapshot?,
        flowSnapshot: FlowSnapshot?,
        volProxyPct: Double?,
        nowMs: Long
    ) {
        val pending = state.pendingOrder
        state.pendingOrder = null
        globalMutex.withLock { stats.ordersCanceled += 1 }
        if (pending != null) {
            val orderAgeMs = nowMs - pending.placedAt
            csvLogger?.logOrderEvent(
                timestampMs = nowMs,
                symbol = state.symbol,
                event = "CANCEL",
                side = pending.side,
                price = pending.price,
                quantity = pending.quantity,
                filledQuantity = pending.filledQuantity,
                orderAgeMs = orderAgeMs,
                reason = reason,
                snapshot = snapshot,
                flowSnapshot = flowSnapshot,
                volProxyPct = volProxyPct
            )
        }
        logSymbol(state.symbol, "ENTRY_CANCEL reason=$reason")
    }

    private fun updateStateWindows(
        state: SymbolState,
        snapshot: BookSnapshot,
        trades: List<AggTrade>,
        nowMs: Long
    ) {
        state.midSamples.add(PriceSample(nowMs, snapshot.mid))
        while (state.midSamples.isNotEmpty() && nowMs - state.midSamples.first().timestamp > config.volWindowMs) {
            state.midSamples.removeFirst()
        }
        if (trades.isNotEmpty()) {
            val lastTrade = trades.maxByOrNull { it.tradeId }
            if (lastTrade != null) {
                state.lastTradePrice = lastTrade.price
                state.lastTradeTimestamp = lastTrade.timestamp
            }
            trades.forEach { state.tradeWindow.add(it) }
        }
        while (state.tradeWindow.isNotEmpty() && nowMs - state.tradeWindow.first().timestamp > config.tradeWindowMs) {
            state.tradeWindow.removeFirst()
        }
    }

    private fun computeFlowSnapshot(trades: Collection<AggTrade>): FlowSnapshot {
        if (trades.isEmpty()) return FlowSnapshot(0.0, 0.0, 0.0, 0.0, 0.0, 0)

        val buyQty = trades.sumOf { if (!it.isBuyerMaker) it.quantity else 0.0 }
        val sellQty = trades.sumOf { if (it.isBuyerMaker) it.quantity else 0.0 }
        val totalQty = buyQty + sellQty
        val totalNotional = trades.sumOf { it.price * it.quantity }

        val rawImb = if (totalQty <= 0.0) 0.0 else (buyQty - sellQty) / totalQty

        val flowOk = trades.size >= config.minFlowTrades && totalNotional >= config.minFlowNotional
        val imb = if (flowOk) rawImb else 0.0

        return FlowSnapshot(
            buyQty = buyQty,
            sellQty = sellQty,
            totalQty = totalQty,
            totalNotional = totalNotional,
            imbalance = imb,
            tradeCount = trades.size
        )
    }


    private fun resolveEntryPrice(side: Side, snapshot: BookSnapshot): Double {
        val offset = computeEntryOffset(snapshot.spread)
        return if (side == Side.LONG) {
            snapshot.bestBid + offset
        } else {
            snapshot.bestAsk - offset
        }
    }

    private fun computeEntryOffset(spread: Double): Double {
        if (spread <= 0.0) return 0.0
        val offsetPct = config.entryOffsetPctOfSpread.coerceAtLeast(0.0)
        val rawOffset = spread * offsetPct
        val maxOffset = spread * 0.49
        return min(rawOffset, maxOffset)
    }

    private fun resolveExitPrice(
        side: Side,
        snapshot: BookSnapshot,
        lastTradePrice: Double?
    ): ExitResolution {
        return when (config.exitPriceBasis) {
            ExitPriceBasis.BEST_BID_ASK -> {
                val price = if (side == Side.LONG) snapshot.bestBid else snapshot.bestAsk
                ExitResolution(price, ExitPriceBasis.BEST_BID_ASK)
            }
            ExitPriceBasis.MID -> ExitResolution(snapshot.mid, ExitPriceBasis.MID)
            ExitPriceBasis.LAST_TRADE -> {
                if (lastTradePrice != null) {
                    ExitResolution(lastTradePrice, ExitPriceBasis.LAST_TRADE)
                } else {
                    ExitResolution(snapshot.mid, ExitPriceBasis.MID)
                }
            }
        }
    }

    private suspend fun safeDepth(symbol: String): OrderBookDepth? {
        return try {
            requestSemaphore.withPermit { orderBookService.getDepth(symbol, config.depthLimit) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logSymbol(symbol, "Depth error: ${e.message}")
            null
        }
    }

    private suspend fun safeAggTrades(symbol: String, lastTradeId: Long?): List<AggTrade> {
        return try {
            requestSemaphore.withPermit { marketDataService.getAggTrades(symbol, lastTradeId?.plus(1), config.aggTradesLimit) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logSymbol(symbol, "AggTrades error: ${e.message}")
            emptyList()
        }
    }

    private fun buildSnapshot(depth: OrderBookDepth, timestamp: Long): BookSnapshot? {
        val bestBid = depth.bids.firstOrNull() ?: return null
        val bestAsk = depth.asks.firstOrNull() ?: return null
        val mid = computeMid(bestBid.price, bestAsk.price)
        val spread = computeSpread(bestBid.price, bestAsk.price)
        if (spread <= 0.0 || mid <= 0.0) return null
        val spreadPct = computeSpreadPct(mid, spread)
        val imbalance = computeImbalance(depth.bids, depth.asks, config.depthLevels)
        val microPrice = computeMicroPrice(bestBid.price, bestAsk.price, bestBid.quantity, bestAsk.quantity)
        val microEdge = computeMicroEdge(microPrice, mid, spread)
        val bidDepthNotional = computeNotionalDepth(depth.bids, config.depthLevels)
        val askDepthNotional = computeNotionalDepth(depth.asks, config.depthLevels)
        return BookSnapshot(
            timestamp = timestamp,
            bestBid = bestBid.price,
            bestAsk = bestAsk.price,
            bidQty1 = bestBid.quantity,
            askQty1 = bestAsk.quantity,
            mid = mid,
            spread = spread,
            spreadPct = spreadPct,
            imbalance = imbalance,
            microPrice = microPrice,
            microEdge = microEdge,
            bidDepthNotional = bidDepthNotional,
            askDepthNotional = askDepthNotional
        )
    }

    private fun isEligibleSymbol(symbol: String): Boolean {
        if (!symbol.endsWith(config.quoteAsset)) return false
        if (config.excludedSymbols.contains(symbol)) return false
        val base = symbol.removeSuffix(config.quoteAsset)
        val bannedSuffixes = listOf("UP", "DOWN", "BULL", "BEAR")
        if (bannedSuffixes.any { base.endsWith(it) }) return false
        return true
    }

    private fun spreadInBand(spreadPct: Double): Boolean {
        return spreadPct >= config.minSpreadPct && spreadPct <= config.maxSpreadPct
    }

    private data class ExitResolution(val price: Double, val basis: ExitPriceBasis)

    private fun updateSignalStreaks(state: SymbolState, signal: SignalStatus) {
        state.longSignalStreak = if (signal.long) state.longSignalStreak + 1 else 0
        state.shortSignalStreak = if (signal.short) state.shortSignalStreak + 1 else 0
    }

    private suspend fun logSummary() {
        val snapshot = globalMutex.withLock {
            SummarySnapshot(
                equity = globalState.equity,
                peakEquity = globalState.peakEquity,
                openPositions = globalState.openPositions,
                killSwitch = globalState.killSwitch,
                stats = stats.copy()
            )
        }
        val fillRate = if (snapshot.stats.ordersPlaced == 0L) 0.0 else {
            snapshot.stats.ordersFilled.toDouble() / snapshot.stats.ordersPlaced.toDouble()
        }
        val winRate = if (snapshot.stats.trades == 0L) 0.0 else {
            snapshot.stats.wins.toDouble() / snapshot.stats.trades.toDouble()
        }
        val avgFillMs = if (snapshot.stats.fillTimeSamples == 0L) 0.0 else {
            snapshot.stats.fillTimeSumMs.toDouble() / snapshot.stats.fillTimeSamples.toDouble()
        }
        val avgAdverse1s = if (snapshot.stats.adverse1sCount == 0L) 0.0 else {
            snapshot.stats.adverse1sSum / snapshot.stats.adverse1sCount.toDouble()
        }
        val avgAdverse5s = if (snapshot.stats.adverse5sCount == 0L) 0.0 else {
            snapshot.stats.adverse5sSum / snapshot.stats.adverse5sCount.toDouble()
        }

        csvLogger?.logEquitySnapshot(
            timestampMs = System.currentTimeMillis(),
            equity = snapshot.equity,
            peakEquity = snapshot.peakEquity,
            openPositions = snapshot.openPositions,
            killSwitch = snapshot.killSwitch,
            stats = snapshot.stats,
            fillRate = fillRate,
            winRate = winRate,
            avgFillMs = avgFillMs,
            avgAdverse1s = avgAdverse1s,
            avgAdverse5s = avgAdverse5s
        )

        logGlobal(
            "Stats equity=${fmt(snapshot.equity)} open=${snapshot.openPositions} " +
                "orders=${snapshot.stats.ordersPlaced} fills=${snapshot.stats.ordersFilled} " +
                "fillRate=${fmtPct(fillRate)} trades=${snapshot.stats.trades} winRate=${fmtPct(winRate)} " +
                "netPnl=${fmt(snapshot.stats.netPnl)} avgFillMs=${avgFillMs.toLong()} " +
                "adv1s=${fmtPct(avgAdverse1s)} adv5s=${fmtPct(avgAdverse5s)} kill=${snapshot.killSwitch}"
        )
    }

    private fun logGlobal(message: String) {
        println("[${System.currentTimeMillis()}] $message")
    }

    private fun logSymbol(symbol: String, message: String) {
        println("[${System.currentTimeMillis()}] $symbol $message")
    }

    private fun fmt(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "n/a"
        return String.format("%.6f", value)
    }

    private fun fmtPct(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "n/a"
        return String.format("%.4f", value)
    }

    private data class SignalStatus(val long: Boolean, val short: Boolean)

    private data class SymbolCandidate(
        val symbol: String,
        val quoteVolume: Double,
        val spreadPct: Double,
        val depthNotional: Double
    )

    private data class SummarySnapshot(
        val equity: Double,
        val peakEquity: Double,
        val openPositions: Int,
        val killSwitch: Boolean,
        val stats: ScalperStats
    )
}
