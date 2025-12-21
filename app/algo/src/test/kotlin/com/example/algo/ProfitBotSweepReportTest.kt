package com.example.algo

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfitBotSweepReportTest {
    @Test
    fun profitBotSweepReport_printsWhenFilteredEmpty() {
        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(buffer))
            ProfitBotSweepReport.print(emptyList())
        } finally {
            System.setOut(originalOut)
        }
        assertTrue(buffer.toString().contains("No profit bots passed the filter."))
    }
}
