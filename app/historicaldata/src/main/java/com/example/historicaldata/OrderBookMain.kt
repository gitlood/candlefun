package com.example.historicaldata

import com.example.historicaldata.interfaces.OrderBookRepository
import com.example.historicaldata.util.OrderBookCollector
import com.example.network.interfaces.BinanceOrderBookService
import com.example.platformutil.DEFAULT_SYMBOLS
import com.example.platformutil.orderBookDbPath
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun runOrderBookCollector(args: Array<String>) = runBlocking {
    val symbols = DEFAULT_SYMBOLS
    val pollMillis = args.firstOrNull { it.startsWith("--poll-ms=") }
        ?.substringAfter("=")
        ?.toLongOrNull() ?: 5_000L
    val maxIterations = args.firstOrNull { it.startsWith("--iterations=") }
        ?.substringAfter("=")
        ?.toIntOrNull()

    val orderBookService = BinanceOrderBookService.create()
    val repository: OrderBookRepository = OrderBookRepositoryImpl()
    val collector = OrderBookCollector(orderBookService, repository)

    var iterations = 0
    while (maxIterations == null || iterations < maxIterations) {
        for (symbol in symbols) {
            val dbPath = orderBookDbPath(symbol)
            Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(OrderBookSnapshots)
            }
            try {
                collector.collectOnce(symbol = symbol, limit = 100)
            } catch (e: Exception) {
                println("OrderBook fetch failed for $symbol: ${e.message}")
            }
        }
        kotlinx.coroutines.delay(pollMillis)
        iterations++
    }
}

fun main(args: Array<String>) = runOrderBookCollector(args)
