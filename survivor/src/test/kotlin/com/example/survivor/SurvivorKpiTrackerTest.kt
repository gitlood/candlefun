package com.example.survivor

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.enums.OrderSide
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurvivorKpiTrackerTest {
    @Test
    fun `kpi tracker applies funding and borrow costs`() {
        val config = SurvivorConfig(symbol = "BTCUSDT", borrowFeePctPerDay = 0.001)
        val tracker = SurvivorKpiTracker(config)
        tracker.onFill(
            SurvivorFill(
                orderId = 1L,
                symbol = Symbol.of("BTCUSDT"),
                side = OrderSide.BUY,
                price = Price.fromDouble(100.0),
                quantity = Qty.fromDouble(2.0),
                fillTimeMs = 1_000L
            )
        )
        val fundingTs = 2_000L
        tracker.onMark(
            snapshot(
                ts = fundingTs,
                funding = 0.01,
                nextFunding = fundingTs
            ),
            SurvivorSide.LONG_PERP
        )
        tracker.onMark(
            snapshot(
                ts = fundingTs + 86_400_000L,
                funding = 0.0,
                nextFunding = fundingTs + 86_400_000L
            ),
            SurvivorSide.LONG_PERP
        )

        val summary = tracker.summary()
        assertTrue(summary.realizedFunding < 0.0)
        assertTrue(summary.borrowCosts > 0.0)
        assertNotNull(summary.netCarry)
    }

    @Test
    fun `kpi tracker records cancels and position updates`() {
        val config = SurvivorConfig(symbol = "BTCUSDT")
        val tracker = SurvivorKpiTracker(config)
        tracker.onOrderPlaced(1L, snapshot(ts = 1_000L, funding = 0.0, nextFunding = 2_000L), SurvivorSide.LONG_PERP)
        tracker.onOrderCanceled(1L, stale = true)
        tracker.onPositionUpdate("BTCUSDT", qty = 2.0, avgPrice = 100.0)
        tracker.onMark(snapshot(ts = 2_000L, funding = 0.0, nextFunding = 3_000L), SurvivorSide.LONG_PERP)

        val summary = tracker.summary()
        assertNotNull(summary.cancelRate)
        assertNotNull(summary.staleCancelRate)
    }

    private fun snapshot(ts: Long, funding: Double, nextFunding: Long): SurvivorSnapshot {
        return SurvivorSnapshot(
            symbol = "BTCUSDT",
            timestampMs = ts,
            fundingRate = funding,
            nextFundingTimeMs = nextFunding,
            markPrice = 100.0,
            indexPrice = 100.0,
            spreadPct = 0.0001,
            volatility = 0.01,
            openInterest = 1000.0
        )
    }
}
