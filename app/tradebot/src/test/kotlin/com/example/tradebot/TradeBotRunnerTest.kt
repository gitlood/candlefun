package com.example.tradebot

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.model.BotSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeBotRunnerTest {
    @Test
    fun tradeBotRunner_requiredBarsUsesMaxAndMinimum() {
        val spec = BotSpec(
            name = "bot",
            cfg = AlgoConfig(
                backtest = BacktestConfig(lookbackMinutes = 1, intervalMillis = 60_000L),
                eventStudy = EventStudyConfig(patternBars = 2, contextBars = 2)
            ),
            patterns = emptySet()
        )

        val method = TradeBotRunner::class.java.getDeclaredMethod("requiredBarsFor", BotSpec::class.java)
        method.isAccessible = true
        val result = method.invoke(TradeBotRunner, spec) as Int
        assertEquals(4, result)

        val barsMethod = TradeBotRunner::class.java.getDeclaredMethod(
            "barsFromMinutes",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )
        barsMethod.isAccessible = true
        val bars = barsMethod.invoke(TradeBotRunner, 0, 60_000L) as Int
        assertEquals(1, bars)
    }
}
