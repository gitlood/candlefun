package com.example.network.candlecollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SymbolLoggerTest {

    @Test
    fun `info logs only once within interval`() {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        try {
            val logger = SymbolLogger("BTCUSDT", "1m", statusEveryMs = 60_000L, errorEveryMs = 60_000L)
            logger.info("first")
            logger.info("second")
        } finally {
            System.setOut(original)
        }

        val lines = out.toString().trim().lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
    }

    @Test
    fun `error logs to stderr`() {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err))
        try {
            val logger = SymbolLogger("ETHUSDT", "5m", statusEveryMs = 0L, errorEveryMs = 0L)
            logger.error("oops")
        } finally {
            System.setErr(original)
        }

        val output = err.toString()
        assertTrue(output.contains("ERROR"))
    }
}
