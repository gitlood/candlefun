package com.example.algo

import com.example.platformutil.AlgoConfig
import kotlin.math.max

data class ProfitBotRow(
    val cfg: AlgoConfig,
    val pattern: String,
    val tpPct: Double,
    val trades: Int,
    val winRate: Double,
    val avgNet: Double,
    val medNet: Double,
    val sumNet: Double,
    val compNet: Double,
    val tp: Int,
    val sl: Int,
    val hz: Int,
)

data class SweepReportConfig(
    val minTradesPerBot: Int = 20,
    val minCompNet: Double = 0.0,
    val topNOverall: Int = 100,
    val topNPerConfig: Int = 3,
)

object ProfitBotSweepReport {

    fun print(all: List<ProfitBotRow>, cfg: SweepReportConfig = SweepReportConfig()) {
        println("\n\n============================================================")
        println("=== FINAL SWEEP REPORT (PROFIT BOTS) ===")
        println("Bots total: ${all.size}")
        println("Filter: trades>=${cfg.minTradesPerBot} & compNet>${pct(cfg.minCompNet)}")
        println("============================================================")

        val filtered = all
            .filter { it.trades >= cfg.minTradesPerBot && it.compNet > cfg.minCompNet }
            .sortedWith(compareByDescending<ProfitBotRow> { it.compNet }.thenByDescending { it.sumNet })

        if (filtered.isEmpty()) {
            println("No profit bots passed the filter.")
            println("============================================================")
            return
        }

        fun short(s: String, n: Int): String {
            if (n <= 3) return s.take(max(0, n))
            return if (s.length <= n) s.padEnd(n) else (s.take(n - 1) + "…")
        }

        fun printHeader(title: String) {
            println("\n$title")
            println("#   compNet   sumNet   win%  trades  TP     SL     HZ   tp%   horizon  configId                          pattern")
            println("------------------------------------------------------------------------------------------------------------------")
        }

        // 1) Top overall
        printHeader("TOP ${cfg.topNOverall} OVERALL")
        filtered.take(cfg.topNOverall).forEachIndexed { i, r ->
            println(
                "${(i + 1).toString().padEnd(3)} " +
                        "${pct(r.compNet).padStart(8)} " +
                        "${pct(r.sumNet).padStart(7)} " +
                        "${pct(r.winRate).padStart(6)} " +
                        "${r.trades.toString().padStart(6)}  " +
                        "${r.tp.toString().padStart(5)} " +
                        "${r.sl.toString().padStart(6)} " +
                        "${r.hz.toString().padStart(6)}  " +
                        "${fmtPct(r.tpPct).padStart(5)}  " +
                        "${r.cfg.backtest.horizonMinutes.toString().padStart(7)}m  " +
                        "${short(r.cfg.id(), 30).padEnd(30)}  " +
                        short(r.pattern, 32)
            )
        }

        // 2) Best per config (top K)
        val byCfg = filtered.groupBy { it.cfg.id() }
        val bestPerCfg = byCfg.entries
            .map { (id, rows) -> id to rows.sortedByDescending { it.compNet } }
            .sortedByDescending { (_, rows) -> rows.firstOrNull()?.compNet ?: Double.NEGATIVE_INFINITY }

        println("\n============================================================")
        println("=== BEST PER CONFIG (TOP ${cfg.topNPerConfig} PATTERNS EACH) ===")
        println("============================================================")

        bestPerCfg.forEach { (cfgId, rows) ->
            println("\n--- $cfgId ---")
            rows.take(cfg.topNPerConfig).forEachIndexed { j, r ->
                println(
                    "  ${(j + 1)}. compNet=${pct(r.compNet)} sumNet=${pct(r.sumNet)} win=${pct(r.winRate)} " +
                            "trades=${r.trades} tp=${fmtPct(r.tpPct)} pattern=${r.pattern}"
                )
            }
        }

        println("\n============================================================")
        println("Done.")
        println("============================================================")
    }

    private fun pct(x: Double): String = String.format("%.2f%%", x * 100.0)
    private fun fmtPct(tpPct: Double): String = String.format("%.1f", tpPct)
}
