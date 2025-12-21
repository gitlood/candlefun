package com.example.historicaldata

import com.example.network.model.KlineResponse
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.io.path.createTempFile

internal fun withTempCandleDb(block: (File) -> Unit) {
    val dbFile = createTempFile("candles-test", ".db").toFile()
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
    transaction { SchemaUtils.create(Candles) }
    block(dbFile)
    dbFile.delete()
}

internal fun kline(openTime: Long): KlineResponse {
    return KlineResponse(
        openTime = openTime,
        open = "1.0",
        high = "2.0",
        low = "0.5",
        close = "1.5",
        volume = "10.0",
        closeTime = openTime + 60_000L,
        quoteAssetVolume = "20.0",
        numberOfTrades = 1,
        takerBuyBaseAssetVolume = "5.0",
        takerBuyQuoteAssetVolume = "7.0",
        ignore = "0"
    )
}
