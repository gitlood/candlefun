package com.example.algo

import com.example.algo.model.ReportParams
import com.example.algo.profitgroups.BreakoutPrinter
import com.example.platformutil.ProfitGroupSort
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class BreakoutPrinterTest {
    @Test
    fun breakoutPrinter_outputsSummary() {
        val printer = BreakoutPrinter()
        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(buffer))
            val group = breakoutGroup(entryOpenTime = 1L, windowEnd = 2L, gain = 0.1)
            val params = ReportParams(
                horizonMinutes = 10,
                horizonBars = 2,
                thresholdsPct = doubleArrayOf(0.05),
                localLowLookBackMinutes = 0,
                lookBackBars = 0,
                requireContinuous = true,
                dedupeOverlappingWindows = false,
                sortBy = ProfitGroupSort.GAIN_DESC.name,
                intervalMillis = 60_000L,
                maxGroupsToPrint = 1,
                maxDrawdownPctAllowed = 0.2
            )
            printer.printReport(listOf(group), totalCandles = 4, rawFoundCount = 1, params = params)
        } finally {
            System.setOut(originalOut)
        }
        val output = buffer.toString()
        assertTrue(output.contains("Historic Profit Group Report"))
        assertTrue(output.contains("Listing groups"))
    }

}
