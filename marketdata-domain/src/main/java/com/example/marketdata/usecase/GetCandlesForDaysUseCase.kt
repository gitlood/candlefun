package com.example.marketdata.usecase

import com.example.marketdata.repository.CandleHistoryRepository
import com.example.platform.model.CandleHistoryItem
import com.example.platform.util.Clock
import com.example.platform.util.SystemClock

class GetCandlesForDaysUseCase(
    private val repo: CandleHistoryRepository,
    private val clock: Clock = SystemClock
) {
    suspend operator fun invoke(symbol: String, days: Int): List<CandleHistoryItem> {
        require(days > 0) { "days must be > 0" }

        val nowMs = clock.nowMs()
        val fromMs = nowMs - days * 24L * 60L * 60L * 1000L

        return repo.getCandlesFrom(
            symbol = symbol.uppercase(),
            fromOpenTimeInclusive = fromMs
        )
    }
}
