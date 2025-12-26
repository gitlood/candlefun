package com.example.marketdata.usecase

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

            assertEquals("BTCUSDT", repo.lastSymbol)
            val expectedFrom = 10_000L - 2L * 24L * 60L * 60L * 1000L
            assertEquals(expectedFrom, repo.lastFrom)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non positive days`() {
        runBlocking {
            val useCase = GetCandlesForDaysUseCase(RecordingRepo())
            useCase("BTCUSDT", days = 0)
        }
    }

    private class RecordingRepo : CandleHistoryRepository {
        var lastSymbol: String? = null
        var lastFrom: Long? = null

        override suspend fun getCandlesFrom(
            symbol: String,
            fromOpenTimeInclusive: Long
        ): List<CandleHistoryItem> {
            lastSymbol = symbol
            lastFrom = fromOpenTimeInclusive
            return emptyList()
        }
    }
}
