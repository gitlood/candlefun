package com.example.marketdata.usecase

import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.CandleHistoryRepository
import com.example.platform.model.CandleHistoryItem
import com.example.platform.util.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GetCandlesForDaysUseCaseTest {

    @Test
    fun `invoke uppercases symbol and uses clock range`() {
        runBlocking {
            val repo = RecordingRepo()
            val clock = object : Clock {
                override fun nowMs(): Long = 10_000L
            }
            val useCase = GetCandlesForDaysUseCase(repo, clock)

            useCase("btcusdt", days = 2)

            assertEquals("BTCUSDT", repo.lastSymbol?.value)
            val expectedFrom = 10_000L - 2L * 24L * 60L * 60L * 1000L
            assertEquals(expectedFrom, repo.lastFrom)
            assertEquals(10_000L, repo.lastTo)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non positive days`() {
        runBlocking {
            val useCase = GetCandlesForDaysUseCase(RecordingRepo())
            useCase("BTCUSDT", days = 0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects excessively large ranges`() {
        runBlocking {
            val useCase = GetCandlesForDaysUseCase(RecordingRepo())
            useCase("BTCUSDT", days = 10_000)
        }
    }

    private class RecordingRepo : CandleHistoryRepository {
        var lastSymbol: Symbol? = null
        var lastFrom: Long? = null
        var lastTo: Long? = null

        override suspend fun getCandles(
            symbol: Symbol,
            fromOpenTimeInclusive: Long,
            toOpenTimeExclusive: Long?,
            limit: Int?
        ): List<CandleHistoryItem> {
            lastSymbol = symbol
            lastFrom = fromOpenTimeInclusive
            lastTo = toOpenTimeExclusive
            return emptyList()
        }
    }
}
