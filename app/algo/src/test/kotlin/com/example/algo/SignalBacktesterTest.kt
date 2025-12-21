package com.example.algo

import com.example.algo.backtest.SignalBacktester
import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.SignalConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalBacktesterTest {
    @Test
    fun signalBacktester_parsesPatternQuery() {
        val method = SignalBacktester::class.java.getDeclaredMethod("parsePatternQuery", String::class.java)
        method.isAccessible = true
        val query = method.invoke(SignalBacktester, "pattern=seq_R1.R2|last_D")
        assertNotNull(query)
        val normalizedField = query.javaClass.getDeclaredField("normalizedKey")
        normalizedField.isAccessible = true
        val normalized = normalizedField.get(query) as String
        assertEquals("seq=R1.R2|last=D", normalized)
    }

    @Test
    fun signalBacktester_returnsEmptyWhenNoPatterns() {
        val candles = listOf(candle(0L), candle(60_000L))
        val rows = SignalBacktester.runPatternBacktests(
            candlesRaw = candles,
            patterns = emptyList(),
            cfg = AlgoConfig(
                backtest = BacktestConfig(intervalMillis = 60_000L),
                signal = SignalConfig()
            ),
            printReport = false
        )
        assertTrue(rows.isEmpty())
    }

}
