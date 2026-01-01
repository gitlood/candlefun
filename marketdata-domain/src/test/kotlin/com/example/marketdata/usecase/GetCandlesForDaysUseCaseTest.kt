package com.example.marketdata.usecase

import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.CandleHistoryRepository
import com.example.platform.model.CandleHistoryItem
import com.example.platform.util.Clock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetCandlesForDaysUseCaseTest {
    @Test
    fun `use case passes correct window`() {
        runBlocking {
            val now = 1_000_000L
            val repo = FakeRepo()
            val useCase = GetCandlesForDaysUseCase(repo, FixedClock(now))

            useCase.invoke(" btcusdt ", 2)

            assertEquals(Symbol("BTCUSDT"), repo.lastSymbol)
            assertEquals(now - 2L * 24L * 60L * 60L * 1000L, repo.lastFromMs)
            assertEquals(now, repo.lastToMs)
        }
    }

    @Test
    fun `use case rejects invalid days`() {
        runBlocking {
            val useCase = GetCandlesForDaysUseCase(FakeRepo(), FixedClock(0L))
            assertFailsWith<IllegalArgumentException> { useCase.invoke("BTCUSDT", 0) }
            assertFailsWith<IllegalArgumentException> { useCase.invoke("BTCUSDT", 4000) }
        }
    }
}

private class FixedClock(private val now: Long) : Clock {
    override fun nowMs(): Long = now
}

private class FakeRepo : CandleHistoryRepository {
    var lastSymbol: Symbol? = null
    var lastFromMs: Long? = null
    var lastToMs: Long? = null

    override suspend fun getCandles(
        symbol: Symbol,
        fromOpenTimeInclusive: Long,
        toOpenTimeExclusive: Long?,
        limit: Int?
    ): List<CandleHistoryItem> {
        lastSymbol = symbol
        lastFromMs = fromOpenTimeInclusive
        lastToMs = toOpenTimeExclusive
        return emptyList()
    }
}
