package com.example.historicaldata

import com.example.historicaldata.interfaces.CandleRepository
import com.example.network.model.KlineResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit

internal class CandleRepositoryImpl : CandleRepository {
    override fun isDatabaseEmpty(): Boolean = transaction {
        Candles.selectAll().empty()
    }

    override fun getLatestCandleOpenTime(): Long? = transaction {
        Candles.selectAll().orderBy(Candles.openTime, SortOrder.DESC).limit(1).firstOrNull()?.get(
            Candles.openTime)
    }

    override fun getOldestCandleOpenTime(): Long? = transaction {
        Candles.selectAll().orderBy(Candles.openTime, SortOrder.ASC).limit(1).firstOrNull()?.get(
            Candles.openTime)
    }

    override fun insertKlines(klines: List<KlineResponse>) {
        transaction {
            Candles.batchInsert(klines, ignore = true) { kline ->
                this[Candles.openTime] = kline.openTime
                this[Candles.open] = kline.open
                this[Candles.high] = kline.high
                this[Candles.low] = kline.low
                this[Candles.close] = kline.close
                this[Candles.volume] = kline.volume
                this[Candles.closeTime] = kline.closeTime
                this[Candles.quoteAssetVolume] = kline.quoteAssetVolume
                this[Candles.numberOfTrades] = kline.numberOfTrades
                this[Candles.takerBuyBaseAssetVolume] = kline.takerBuyBaseAssetVolume
                this[Candles.takerBuyQuoteAssetVolume] = kline.takerBuyQuoteAssetVolume
            }
        }
    }

    override fun cleanupOldCandles(): Int {
        val sixMonthsAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(180)
        return transaction {
            Candles.deleteWhere { Candles.openTime less sixMonthsAgo }
        }
    }
}
