package com.example.historicaldata

import com.example.platformutil.model.OrderBookSnapshot
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderBookRepositoryTest {
    @Test
    fun createConnectsAndStores() {
        val dbFile = createTempFile("orderbook-test", ".db").toFile()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction { SchemaUtils.create(OrderBookSnapshots) }

        val repo = com.example.historicaldata.interfaces.OrderBookRepository.create(dbFile.absolutePath)
        val snapshot = OrderBookSnapshot(
            timestamp = 1L,
            symbol = "ETHUSDT",
            bestBid = 100.0,
            bestAsk = 101.0,
            midPrice = 100.5,
            spread = 1.0,
            bidDepth10 = 10.0,
            askDepth10 = 9.0,
            imbalance10 = 0.1,
            bidDepth20 = 20.0,
            askDepth20 = 18.0,
            imbalance20 = 0.1,
            updateId = 7L
        )
        repo.insertSnapshot(snapshot)

        val loaded = repo.getLatestSnapshot("ETHUSDT")
        assertNotNull(loaded)
        assertEquals(7L, loaded.updateId)

        dbFile.delete()
    }

    @Test
    fun insertAndFetchLatestSnapshot() {
        val dbFile = kotlin.io.path.createTempFile("orderbook-test", ".db").toFile()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(OrderBookSnapshots)
        }

        val repo = OrderBookRepositoryImpl()
        val snapshot = OrderBookSnapshot(
            timestamp = 1_000L,
            symbol = "ETHUSDT",
            bestBid = 100.0,
            bestAsk = 101.0,
            midPrice = 100.5,
            spread = 1.0,
            bidDepth10 = 10.0,
            askDepth10 = 9.0,
            imbalance10 = 0.05,
            bidDepth20 = 20.0,
            askDepth20 = 18.0,
            imbalance20 = 0.05,
            updateId = 42L
        )

        repo.insertSnapshot(snapshot)
        val loaded = repo.getLatestSnapshot("ETHUSDT")

        assertNotNull(loaded)
        assertEquals(1_000L, loaded.timestamp)
        assertEquals(100.0, loaded.bestBid)
        assertEquals(42L, loaded.updateId)

        dbFile.delete()
    }
}
