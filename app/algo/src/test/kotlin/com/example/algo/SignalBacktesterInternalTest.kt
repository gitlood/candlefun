package com.example.algo

import com.example.algo.backtest.SignalBacktester
import com.example.algo.model.BacktestFeatures
import com.example.platformutil.OrderBookSignalConfig
import com.example.platformutil.SignalConfig
import com.example.platformutil.model.Candle
import com.example.platformutil.model.OrderBookSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SignalBacktesterInternalTest {
    @Test
    fun parsePatternQuery_normalizesTokens() {
        val method = SignalBacktester::class.java.getDeclaredMethod("parsePatternQuery", String::class.java)
        method.isAccessible = true
        val query = method.invoke(SignalBacktester, "pattern=seq_R1.R2|last_D|ret=DN1")
        assertNotNull(query)
        val normalizedField = query.javaClass.getDeclaredField("normalizedKey")
        normalizedField.isAccessible = true
        val normalized = normalizedField.get(query) as String
        assertEquals("seq=R1.R2|ret=DN1|last=D", normalized)
    }

    @Test
    fun pickBestMatch_prefersStrictWhenRequested() {
        val parse = SignalBacktester::class.java.getDeclaredMethod("parsePatternQuery", String::class.java)
        parse.isAccessible = true

        val qSeqOnly = parse.invoke(SignalBacktester, "seq=R1")
        val qSeqLast = parse.invoke(SignalBacktester, "seq=R1|last=D")
        val qFull = parse.invoke(SignalBacktester, "seq=R1|last=D|ret=DN1")

        val queries = listOf(qSeqOnly, qSeqLast, qFull).mapNotNull { it }

        val keyBundleClass = Class.forName("com.example.algo.backtest.SignalBacktester\$KeyBundle")
        val constructor = keyBundleClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType!!
        )
        constructor.isAccessible = true
        val keyBundle = constructor.newInstance("seq=R1", "D", "DN1", "rng", "vol", 0.5) as Any

        val pickMethod = SignalBacktester::class.java.getDeclaredMethod(
            "pickBestMatch",
            List::class.java,
            keyBundleClass,
            Boolean::class.javaPrimitiveType!!
        )
        pickMethod.isAccessible = true

        val mostSpecific = pickMethod.invoke(SignalBacktester, queries, keyBundle, false)
        val normalizedField = mostSpecific.javaClass.getDeclaredField("normalizedKey")
        normalizedField.isAccessible = true
        assertEquals("seq=R1|ret=DN1|last=D", normalizedField.get(mostSpecific))
    }

    @Test
    fun shouldEnter_respectsOrderBookGate() {
        val method = privateMethod(
            "shouldEnter",
            BacktestFeatures::class.java,
            SignalConfig::class.java,
            OrderBookSignalConfig::class.java
        )

        val features = BacktestFeatures(
            trendSlope = 0.1,
            volStd = 1.0,
            contraction10vLookback = 0.8,
            rangeMean = 1.0,
            volumeZ = 0.0,
            ret5m = 0.0,
            ret15m = 0.0,
            ret30m = 0.0,
            orderBookImbalance10 = 0.2,
            orderBookSpreadBps = 5.0,
            orderBookAvailable = true
        )

        val signalConfig = SignalConfig(ret30mMin = -0.5, volumeZMin = -1.0, contractionMax = 1.5, trendSlopeMin = -1.0)
        val orderBookConfig = OrderBookSignalConfig(enabled = true, minImbalance10 = 0.05, maxSpreadBps = 20.0)

        assertTrue(
            method.invoke(SignalBacktester, features, signalConfig, orderBookConfig) as Boolean,
            "gate should pass when all requirements are met"
        )

        val insufficientImbalance = features.copy(orderBookImbalance10 = 0.0)
        assertFalse(
            method.invoke(SignalBacktester, insufficientImbalance, signalConfig, orderBookConfig) as Boolean,
            "gate should reject when imbalance is below configured minimum"
        )
    }

    @Test
    fun bucket_helpers_classifyValues() {
        val retMethod = privateMethod("bucketRet", Double::class.javaPrimitiveType!!)
        assertEquals("DN2", retMethod.invoke(SignalBacktester, -0.02))
        assertEquals("FL", retMethod.invoke(SignalBacktester, 0.0))
        assertEquals("UP2", retMethod.invoke(SignalBacktester, 0.03))

        val rangeMethod = privateMethod("bucketRange", Double::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!)
        assertEquals("S", rangeMethod.invoke(SignalBacktester, 0.5, 1.0))
        assertEquals("L", rangeMethod.invoke(SignalBacktester, 1.3, 1.0))
        assertEquals("N", rangeMethod.invoke(SignalBacktester, 1.0, 1.0))

        val volMethod = privateMethod("bucketVol", Double::class.javaPrimitiveType!!)
        assertEquals("b", volMethod.invoke(SignalBacktester, 1.3))
        assertEquals("s", volMethod.invoke(SignalBacktester, 0.7))
        assertEquals("n", volMethod.invoke(SignalBacktester, 1.0))
    }

    @Test
    fun buildOrderBookSeries_injectsLatestSnapshot() {
        val candles = listOf(
            candle(0L),
            candle(60_000L),
            candle(120_000L)
        )
        val snapshots = listOf(
            OrderBookSnapshot(
                timestamp = 0L,
                symbol = "ETHUSDT",
                bestBid = 1.0,
                bestAsk = 1.1,
                midPrice = 1.05,
                spread = 0.02,
                bidDepth10 = 0.0,
                askDepth10 = 0.0,
                imbalance10 = 0.1,
                bidDepth20 = 0.0,
                askDepth20 = 0.0,
                imbalance20 = 0.0,
                updateId = 1
            ),
            OrderBookSnapshot(
                timestamp = 60_000L,
                symbol = "ETHUSDT",
                bestBid = 2.0,
                bestAsk = 2.1,
                midPrice = 2.05,
                spread = 0.05,
                bidDepth10 = 0.0,
                askDepth10 = 0.0,
                imbalance10 = 0.2,
                bidDepth20 = 0.0,
                askDepth20 = 0.0,
                imbalance20 = 0.0,
                updateId = 2
            )
        )

        val method = privateMethod("buildOrderBookSeries", List::class.java, List::class.java)
        val series = method.invoke(SignalBacktester, candles, snapshots) ?: fail("order book series should be created")

        val imbalanceField = series.javaClass.getDeclaredField("imbalance10")
        val spreadField = series.javaClass.getDeclaredField("spreadBps")
        val availableField = series.javaClass.getDeclaredField("available")
        imbalanceField.isAccessible = true
        spreadField.isAccessible = true
        availableField.isAccessible = true

        val imbalance = imbalanceField.get(series) as DoubleArray
        val spread = spreadField.get(series) as DoubleArray
        val available = availableField.get(series) as BooleanArray

        assertEquals(0.1, imbalance[0])
        assertEquals(0.2, imbalance[1])
        assertTrue(available[1])
        assertEquals(((0.05 / 2.05) * 10_000).toDouble(), spread[1])
    }

    @Test
    fun candleSeries_localLowAndBreakoutBehaviors() {
        val series = buildSeries(
            listOf(
                candle(0L, high = 1.0, low = 1.0),
                candle(60_000L, high = 1.2, low = 1.1),
                candle(120_000L, high = 1.3, low = 0.9),
                candle(180_000L, high = 1.1, low = 1.05)
            )
        )

        val isLocalLow = privateMethod("isLocalLow", candleSeriesClass, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        assertTrue(isLocalLow.invoke(SignalBacktester, series, 2, 2) as Boolean)
        assertFalse(isLocalLow.invoke(SignalBacktester, series, 3, 2) as Boolean)

        val breakout = privateMethod(
            "findBreakoutTriggerIndex",
            candleSeriesClass,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        )
        val trxIndex = breakout.invoke(SignalBacktester, series, 1, 5, 1.1, false) as Int?
        assertEquals(1, trxIndex)
    }

    @Test
    fun simulateLongPercentTpSl_respectsExitPriorities() {
        val series = buildSeries(
            listOf(
                candle(0L, high = 1.0, low = 1.0, close = 1.0),
                candle(60_000L, high = 1.3, low = 0.8, close = 1.2)
            )
        )
        val method = privateMethod(
            "simulateLongPercentTpSl",
            candleSeriesClass,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            SignalBacktester.EntryPriceMode::class.java,
            Boolean::class.javaPrimitiveType!!
        )

        val entry = method.invoke(
            SignalBacktester,
            series,
            0,
            2,
            10.0,
            5.0,
            0.0,
            0.0,
            SignalBacktester.EntryPriceMode.ENTRY_CANDLE_OPEN,
            false
        )
        assertNotNull(entry)
        val exitKindField = entry.javaClass.getDeclaredField("exitKind")
        exitKindField.isAccessible = true
        assertEquals("TP", exitKindField.get(entry).toString())

        val worstCaseTrade = method.invoke(
            SignalBacktester,
            series,
            0,
            2,
            10.0,
            5.0,
            0.0,
            0.0,
            SignalBacktester.EntryPriceMode.ENTRY_CANDLE_OPEN,
            true
        )
        assertNotNull(worstCaseTrade)
        assertEquals("SL", exitKindField.get(worstCaseTrade).toString())

        val invalid = method.invoke(
            SignalBacktester,
            series,
            5,
            2,
            10.0,
            5.0,
            0.0,
            0.0,
            SignalBacktester.EntryPriceMode.ENTRY_CANDLE_OPEN,
            false
        )
        assertEquals(null, invalid)
    }

    @Test
    fun compoundInTimeOrder_jobsOrdered() {
        val tradeLiteClass = Class.forName("com.example.algo.backtest.SignalBacktester\$TradeLite")
        val exitKindClass = Class.forName("com.example.algo.backtest.SignalBacktester\$ExitKind")
        val constructor = tradeLiteClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            exitKindClass
        ).apply { isAccessible = true }

        val tpKind = exitKindClass.enumConstants.first { (it as Enum<*>).name == "TP" }
        val slKind = exitKindClass.enumConstants.first { (it as Enum<*>).name == "SL" }
        val compileMethod = privateMethod("compoundInTimeOrder", List::class.java)

        val tradeA = constructor.newInstance(1, 2, 0.1, tpKind)
        val tradeB = constructor.newInstance(0, 1, 0.05, slKind)
        val result = compileMethod.invoke(SignalBacktester, listOf(tradeA, tradeB)) as Double
        assertTrue(result > 0)
    }

    @Test
    fun inferIntervalMillis_usesMedianGap() {
        val method = privateMethod("inferIntervalMillis", List::class.java)
        val candles = listOf(
            candle(0L),
            candle(100_000L),
            candle(250_000L),
            candle(450_000L)
        )
        val interval = method.invoke(SignalBacktester, candles) as Long
        assertEquals(150_000L, interval)
    }

    private fun buildSeries(candles: List<Candle>): Any {
        val companionField = candleSeriesClass.getDeclaredField("Companion").apply { isAccessible = true }
        val companionInstance = companionField.get(null)
        val companionClass = companionInstance.javaClass
        val method = companionClass.getDeclaredMethod(
            "from",
            List::class.java,
            Long::class.javaPrimitiveType!!,
            orderBookSeriesClass
        )
        method.isAccessible = true
        return method.invoke(companionInstance, candles, 60_000L, null)!!
    }

    private fun privateMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method =
        SignalBacktester::class.java.getDeclaredMethod(name, *params).apply { isAccessible = true }

    private val candleSeriesClass = Class.forName("com.example.algo.backtest.SignalBacktester\$CandleSeries")
    private val orderBookSeriesClass = Class.forName("com.example.algo.backtest.SignalBacktester\$OrderBookSeries")
}
