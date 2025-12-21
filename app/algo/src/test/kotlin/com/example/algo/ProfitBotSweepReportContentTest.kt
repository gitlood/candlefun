package com.example.algo

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfitBotSweepReportContentTest {
    @Test
    fun profitBotSweepReport_printsSummaryWhenRowsPresent() {
        val row = ProfitBotRow(
            cfg = AlgoConfig(backtest = BacktestConfig(horizonMinutes = 30)),
            pattern = "seq=D0.D0",
            tpPct = 0.8,
            trades = 25,
            winRate = 0.6,
            avgNet = 0.01,
            medNet = 0.01,
            sumNet = 0.2,
            compNet = 0.15,
            tp = 12,
            sl = 5,
            hz = 30
        )

        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(buffer))
            ProfitBotSweepReport.print(listOf(row))
        } finally {
            System.setOut(originalOut)
        }

        val output = buffer.toString()
        assertTrue(output.contains("FINAL SWEEP REPORT"))
        assertTrue(output.contains("TOP"))
        assertTrue(output.contains("seq=D0.D0"))
    }
}
