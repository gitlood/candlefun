package com.example.historicaldata

import com.example.historicaldata.interfaces.CandleRepository
import com.example.historicaldata.util.CandleDataOrchestrator
import com.example.network.interfaces.BinanceApiService
import com.example.network.model.KlineResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleDataOrchestratorTest {
    @Test
    fun candleDataOrchestrator_backfillsWhenDatabaseEmpty() {
        val api = RecordingBinanceApiService()
        val repo = FakeCandleRepository(isEmpty = true, latestOpenTime = null)
        val orchestrator = CandleDataOrchestrator(
            binanceApiService = api,
            candleRepository = repo,
            symbol = "ETHUSDT",
            interval = "1m"
        )

        orchestrator.updateCandles()
        assertEquals(1, api.calls.size)
        assertTrue(api.calls.first().startTime != null)
        assertEquals(0, repo.inserted)
    }

    @Test
    fun candleDataOrchestrator_fetchesLatestWhenDatabaseNotEmpty() {
        val api = RecordingBinanceApiService()
        val repo = FakeCandleRepository(isEmpty = false, latestOpenTime = 123L)
        val orchestrator = CandleDataOrchestrator(
            binanceApiService = api,
            candleRepository = repo,
            symbol = "ETHUSDT",
            interval = "1m"
        )

        orchestrator.updateCandles()
        assertEquals(1, api.calls.size)
        assertEquals(123L, api.calls.first().startTime)
    }

    private data class ApiCall(
        val startTime: Long?,
        val endTime: Long?,
        val limit: Int
    )

    private class RecordingBinanceApiService : BinanceApiService {
        val calls = mutableListOf<ApiCall>()

        override suspend fun getKlines(
            symbol: String,
            interval: String,
            limit: Int,
            startTime: Long?,
            endTime: Long?
        ): List<KlineResponse> {
            calls.add(ApiCall(startTime = startTime, endTime = endTime, limit = limit))
            return emptyList()
        }
    }

    private class FakeCandleRepository(
        private val isEmpty: Boolean,
        private val latestOpenTime: Long?
    ) : CandleRepository {
        var inserted = 0

        override fun isDatabaseEmpty(): Boolean = isEmpty

        override fun getLatestCandleOpenTime(): Long? = latestOpenTime

        override fun getOldestCandleOpenTime(): Long? = latestOpenTime

        override fun insertKlines(klines: List<KlineResponse>) {
            inserted += klines.size
        }

        override fun cleanupOldCandles(): Int = 0
    }
}
