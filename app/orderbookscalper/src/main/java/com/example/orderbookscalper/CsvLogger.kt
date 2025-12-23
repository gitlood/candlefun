package com.example.orderbookscalper

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

class CsvLogger(outputDir: String) {
    val runId: Long = System.currentTimeMillis()

    private val dir = File(outputDir)
    private val ordersFile = File(dir, "orders_$runId.csv")
    private val tradesFile = File(dir, "trades_$runId.csv")
    private val equityFile = File(dir, "equity_$runId.csv")
    private val runInfoFile = File(dir, "run_info_$runId.csv")
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    init {
        if (!dir.exists()) dir.mkdirs()
        writeHeaderIfNeeded(ordersFile, ORDERS_HEADER)
        writeHeaderIfNeeded(tradesFile, TRADES_HEADER)
        writeHeaderIfNeeded(equityFile, EQUITY_HEADER)
        writeHeaderIfNeeded(runInfoFile, RUN_INFO_HEADER)
    }

    @Synchronized
    fun writeRunInfo(config: ScalperConfig) {
        val ts = nowMs()
        val row = listOf(
            ts.toString(),
            iso(ts),
            runId.toString(),
            config.quoteAsset,
            config.fixedSymbols.joinToString("|"),
            config.excludedSymbols.joinToString("|"),
            config.maxActiveSymbols.toString(),
            config.maxScanSymbols.toString(),
            config.minQuoteVolume.toString(),
            config.minTradeCount.toString(),
            config.depthLevels.toString(),
            config.depthLimit.toString(),
            config.selectionIntervalMs.toString(),
            config.pollIntervalMs.toString(),
            config.tradeWindowMs.toString(),
            config.entryTtlMs.toString(),
            config.entryOffsetPctOfSpread.toString(),
            config.maxHoldMs.toString(),
            config.tpMult.toString(),
            config.slMult.toString(),
            config.trailArmMult.toString(),
            config.trailMult.toString(),
            config.imbalanceThresh.toString(),
            config.microEdgeThresh.toString(),
            config.flowImbalanceThresh.toString(),
            config.minSpreadPct.toString(),
            config.maxSpreadPct.toString(),
            config.feePctPerSide.toString(),
            config.exitSlippagePct.toString(),
            config.exitPriceBasis.name,
            config.startingEquity.toString(),
            config.perSymbolMaxNotionalPct.toString(),
            config.maxSymbolNotional.toString(),
            config.maxOpenPositions.toString(),
            config.cooldownMs.toString(),
            config.maxDrawdownPct.toString(),
            config.minDepthNotional.toString(),
            config.minVolProxyPct.toString(),
            config.volWindowMs.toString(),
            config.maxStaleMs.toString(),
            config.aggTradesLimit.toString(),
            config.maxConcurrentRequests.toString(),
            config.statsIntervalMs.toString(),
            config.runMs.toString(),
            config.cancelOnSignalFlip.toString(),
            config.verbose.toString(),
            config.csvEnabled.toString(),
            config.csvDir
        )
        appendRow(runInfoFile, row)
    }

    @Synchronized
    fun logOrderEvent(
        timestampMs: Long,
        symbol: String,
        event: String,
        side: Side,
        price: Double?,
        quantity: Double?,
        filledQuantity: Double?,
        orderAgeMs: Long?,
        reason: String?,
        snapshot: BookSnapshot?,
        flowSnapshot: FlowSnapshot?,
        volProxyPct: Double?
    ) {
        val entryOffset = entryOffset(side, price, snapshot)
        val entryOffsetPct = if (entryOffset != null && snapshot?.spread != null && snapshot.spread > 0.0) {
            entryOffset / snapshot.spread
        } else {
            null
        }
        val row = listOf(
            timestampMs.toString(),
            iso(timestampMs),
            runId.toString(),
            symbol,
            event,
            side.name,
            fmt(price),
            fmt(quantity),
            fmt(filledQuantity),
            orderAgeMs?.toString() ?: "",
            fmt(entryOffset),
            fmt(entryOffsetPct),
            fmt(snapshot?.spread),
            fmt(snapshot?.spreadPct),
            fmt(snapshot?.imbalance),
            fmt(snapshot?.microPrice),
            fmt(snapshot?.microEdge),
            fmt(flowSnapshot?.imbalance),
            fmt(volProxyPct),
            fmt(snapshot?.bestBid),
            fmt(snapshot?.bestAsk),
            fmt(snapshot?.mid),
            fmt(snapshot?.spread),
            fmt(snapshot?.bidQty1),
            fmt(snapshot?.askQty1),
            fmt(snapshot?.bidDepthNotional),
            fmt(snapshot?.askDepthNotional),
            fmt(flowSnapshot?.buyQty),
            fmt(flowSnapshot?.sellQty),
            flowSnapshot?.tradeCount?.toString() ?: "",
            reason ?: ""
        )
        appendRow(ordersFile, row)
    }

    @Synchronized
    fun logTradeExit(
        timestampMs: Long,
        symbol: String,
        side: Side,
        entryTimeMs: Long,
        exitTimeMs: Long,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        tp: Double,
        sl: Double,
        trailArm: Double,
        trailMult: Double,
        entrySpread: Double,
        entryOffset: Double,
        entrySpreadPct: Double,
        entryMid: Double,
        exitMid: Double,
        exitBasis: ExitPriceBasis,
        lastTradePrice: Double?,
        entryImbalance: Double,
        entryMicroPrice: Double?,
        entryMicroEdge: Double?,
        entryFlowImbalance: Double,
        entryVolProxyPct: Double,
        entryBidDepthNotional: Double,
        entryAskDepthNotional: Double,
        grossPct: Double,
        netPct: Double,
        pnl: Double,
        reason: String,
        adverse1sPct: Double?,
        adverse5sPct: Double?
    ) {
        val holdMs = exitTimeMs - entryTimeMs
        val row = listOf(
            timestampMs.toString(),
            iso(timestampMs),
            runId.toString(),
            symbol,
            side.name,
            entryTimeMs.toString(),
            exitTimeMs.toString(),
            holdMs.toString(),
            fmt(entryPrice),
            fmt(exitPrice),
            fmt(quantity),
            fmt(tp),
            fmt(sl),
            fmt(trailArm),
            fmt(trailMult),
            fmt(entrySpread),
            fmt(entryOffset),
            fmt(entrySpreadPct),
            fmt(entryMid),
            fmt(exitMid),
            exitBasis.name,
            fmt(lastTradePrice),
            fmt(entryImbalance),
            fmt(entryMicroPrice),
            fmt(entryMicroEdge),
            fmt(entryFlowImbalance),
            fmt(entryVolProxyPct),
            fmt(entryBidDepthNotional),
            fmt(entryAskDepthNotional),
            fmt(grossPct),
            fmt(netPct),
            fmt(pnl),
            reason,
            fmt(adverse1sPct),
            fmt(adverse5sPct)
        )
        appendRow(tradesFile, row)
    }

    @Synchronized
    fun logEquitySnapshot(
        timestampMs: Long,
        equity: Double,
        peakEquity: Double,
        openPositions: Int,
        killSwitch: Boolean,
        stats: ScalperStats,
        fillRate: Double,
        winRate: Double,
        avgFillMs: Double,
        avgAdverse1s: Double,
        avgAdverse5s: Double
    ) {
        val row = listOf(
            timestampMs.toString(),
            iso(timestampMs),
            runId.toString(),
            fmt(equity),
            fmt(peakEquity),
            openPositions.toString(),
            killSwitch.toString(),
            stats.ordersPlaced.toString(),
            stats.ordersFilled.toString(),
            stats.ordersCanceled.toString(),
            stats.trades.toString(),
            stats.wins.toString(),
            stats.losses.toString(),
            fmt(stats.grossPnl),
            fmt(stats.netPnl),
            fmt(fillRate),
            fmt(winRate),
            fmt(avgFillMs),
            fmt(avgAdverse1s),
            fmt(avgAdverse5s)
        )
        appendRow(equityFile, row)
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun iso(timestampMs: Long): String = isoFormatter.format(Instant.ofEpochMilli(timestampMs))

    private fun fmt(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return ""
        return String.format(Locale.US, "%.10f", value)
    }

    private fun entryOffset(side: Side, price: Double?, snapshot: BookSnapshot?): Double? {
        if (price == null || snapshot == null) return null
        return if (side == Side.LONG) {
            price - snapshot.bestBid
        } else {
            snapshot.bestAsk - price
        }
    }

    private fun appendRow(file: File, values: List<String>) {
        val row = values.joinToString(",") { csv(it) }
        BufferedWriter(FileWriter(file, true)).use { writer ->
            writer.append(row)
            writer.newLine()
        }
    }

    private fun writeHeaderIfNeeded(file: File, header: List<String>) {
        if (file.exists()) return
        appendRow(file, header)
    }

    private fun csv(value: String): String {
        val needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        if (!needsQuoting) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    companion object {
        private val ORDERS_HEADER = listOf(
            "timestamp_ms",
            "timestamp_iso",
            "run_id",
            "symbol",
            "event",
            "side",
            "price",
            "quantity",
            "filled_quantity",
            "order_age_ms",
            "entry_offset",
            "entry_offset_pct",
            "entry_spread",
            "spread_pct",
            "imbalance",
            "micro_price",
            "micro_edge",
            "flow_imbalance",
            "vol_proxy_pct",
            "best_bid",
            "best_ask",
            "mid",
            "spread",
            "bid_qty1",
            "ask_qty1",
            "bid_depth_notional",
            "ask_depth_notional",
            "flow_buy_qty",
            "flow_sell_qty",
            "flow_trade_count",
            "reason"
        )

        private val TRADES_HEADER = listOf(
            "timestamp_ms",
            "timestamp_iso",
            "run_id",
            "symbol",
            "side",
            "entry_time_ms",
            "exit_time_ms",
            "hold_ms",
            "entry_price",
            "exit_price",
            "quantity",
            "tp",
            "sl",
            "trail_arm",
            "trail_mult",
            "entry_spread",
            "entry_spread_pct",
            "entry_mid",
            "exit_mid",
            "exit_basis",
            "last_trade_price",
            "entry_imbalance",
            "entry_micro_price",
            "entry_micro_edge",
            "entry_flow_imbalance",
            "entry_vol_proxy_pct",
            "entry_bid_depth_notional",
            "entry_ask_depth_notional",
            "gross_pct",
            "net_pct",
            "pnl",
            "reason",
            "adverse_1s_pct",
            "adverse_5s_pct"
        )

        private val EQUITY_HEADER = listOf(
            "timestamp_ms",
            "timestamp_iso",
            "run_id",
            "equity",
            "peak_equity",
            "open_positions",
            "kill_switch",
            "orders_placed",
            "orders_filled",
            "orders_canceled",
            "trades",
            "wins",
            "losses",
            "gross_pnl",
            "net_pnl",
            "fill_rate",
            "win_rate",
            "avg_fill_ms",
            "avg_adverse_1s",
            "avg_adverse_5s"
        )

        private val RUN_INFO_HEADER = listOf(
            "timestamp_ms",
            "timestamp_iso",
            "run_id",
            "quote_asset",
            "fixed_symbols",
            "excluded_symbols",
            "max_active_symbols",
            "max_scan_symbols",
            "min_quote_volume",
            "min_trade_count",
            "depth_levels",
            "depth_limit",
            "selection_interval_ms",
            "poll_interval_ms",
            "trade_window_ms",
            "entry_ttl_ms",
            "entry_offset_pct",
            "max_hold_ms",
            "tp_mult",
            "sl_mult",
            "trail_arm_mult",
            "trail_mult",
            "imbalance_thresh",
            "micro_edge_thresh",
            "flow_imbalance_thresh",
            "min_spread_pct",
            "max_spread_pct",
            "fee_pct_per_side",
            "exit_slippage_pct",
            "exit_price_basis",
            "starting_equity",
            "per_symbol_max_notional_pct",
            "max_symbol_notional",
            "max_open_positions",
            "cooldown_ms",
            "max_drawdown_pct",
            "min_depth_notional",
            "min_vol_proxy_pct",
            "vol_window_ms",
            "max_stale_ms",
            "agg_trades_limit",
            "max_concurrent_requests",
            "stats_interval_ms",
            "run_ms",
            "cancel_on_signal_flip",
            "alert_enabled",
            "smtp_host",
            "smtp_port",
            "smtp_tls",
            "alert_from",
            "alert_to",
            "alert_subject",
            "verbose",
            "csv_enabled",
            "csv_dir"
        )
    }
}
