package com.example.marketdata.usecase

import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.CandleHistoryRepository
import com.example.platform.model.CandleHistoryItem
import com.example.platform.util.Clock
import com.example.platform.util.SystemClock

class GetCandlesForDaysUseCase(
    private val repo: CandleHistoryRepository,
    private val clock: Clock = SystemClock
) {
    suspend operator fun invoke(symbol: String, days: Int): List<CandleHistoryItem> {
        require(days in 1..3650) { "days must be between 1 and 3650" }

        val nowMs = clock.nowMs()
        val fromMs = nowMs - days * 24L * 60L * 60L * 1000L

        return repo.getCandles(
            symbol = symbol.asSymbol(),
            fromOpenTimeInclusive = fromMs,
            toOpenTimeExclusive = nowMs,
            limit = null
        )
    }
}
