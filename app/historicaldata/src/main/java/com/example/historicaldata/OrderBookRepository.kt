package com.example.historicaldata

import com.example.historicaldata.interfaces.OrderBookRepository
import com.example.platformutil.model.OrderBookSnapshot
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction

class OrderBookRepositoryImpl : OrderBookRepository {
    override fun insertSnapshot(snapshot: OrderBookSnapshot) {
        transaction {
            OrderBookSnapshots.insertIgnore {
                it[timestamp] = snapshot.timestamp
                it[symbol] = snapshot.symbol
                it[bestBid] = snapshot.bestBid
                it[bestAsk] = snapshot.bestAsk
                it[midPrice] = snapshot.midPrice
                it[spread] = snapshot.spread
                it[bidDepth10] = snapshot.bidDepth10
                it[askDepth10] = snapshot.askDepth10
                it[imbalance10] = snapshot.imbalance10
                it[bidDepth20] = snapshot.bidDepth20
                it[askDepth20] = snapshot.askDepth20
                it[imbalance20] = snapshot.imbalance20
                it[updateId] = snapshot.updateId
            }
        }
    }

    override fun getLatestSnapshot(symbol: String): OrderBookSnapshot? {
        return transaction {
            OrderBookSnapshots
                .select { OrderBookSnapshots.symbol eq symbol }
                .orderBy(OrderBookSnapshots.timestamp, SortOrder.DESC)
                .limit(1)
                .map { rowToSnapshot(it) }
                .firstOrNull()
        }
    }

    override fun getSnapshotsSince(symbol: String, sinceTime: Long): List<OrderBookSnapshot> {
        return transaction {
            OrderBookSnapshots
                .select { (OrderBookSnapshots.symbol eq symbol) and (OrderBookSnapshots.timestamp greaterEq sinceTime) }
                .orderBy(OrderBookSnapshots.timestamp, SortOrder.ASC)
                .map { rowToSnapshot(it) }
        }
    }

    private fun rowToSnapshot(row: org.jetbrains.exposed.sql.ResultRow): OrderBookSnapshot {
        return OrderBookSnapshot(
            timestamp = row[OrderBookSnapshots.timestamp],
            symbol = row[OrderBookSnapshots.symbol],
            bestBid = row[OrderBookSnapshots.bestBid],
            bestAsk = row[OrderBookSnapshots.bestAsk],
            midPrice = row[OrderBookSnapshots.midPrice],
            spread = row[OrderBookSnapshots.spread],
            bidDepth10 = row[OrderBookSnapshots.bidDepth10],
            askDepth10 = row[OrderBookSnapshots.askDepth10],
            imbalance10 = row[OrderBookSnapshots.imbalance10],
            bidDepth20 = row[OrderBookSnapshots.bidDepth20],
            askDepth20 = row[OrderBookSnapshots.askDepth20],
            imbalance20 = row[OrderBookSnapshots.imbalance20],
            updateId = row[OrderBookSnapshots.updateId]
        )
    }
}
