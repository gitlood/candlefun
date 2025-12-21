package com.example.historicaldata.interfaces

import com.example.platformutil.model.OrderBookSnapshot

interface OrderBookRepository {
    fun insertSnapshot(snapshot: OrderBookSnapshot)
    fun getLatestSnapshot(symbol: String): OrderBookSnapshot?
    fun getSnapshotsSince(symbol: String, sinceTime: Long): List<OrderBookSnapshot>

    companion object {
        fun create(dbPath: String): OrderBookRepository {
            org.jetbrains.exposed.sql.Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
            return com.example.historicaldata.OrderBookRepositoryImpl()
        }
    }
}
