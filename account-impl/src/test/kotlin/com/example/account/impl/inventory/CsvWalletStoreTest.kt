package com.example.account.impl.inventory

import com.example.account.domain.inventory.CsvWalletRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvWalletStoreTest {
    @Test
    fun `save and load round trip`() {
        val temp = Files.createTempFile("wallet", ".csv").toFile()
        temp.deleteOnExit()
        val store = CsvWalletStore(temp.absolutePath)
        val rows = listOf(
            CsvWalletRow(
                asset = "USDT",
                free = 1.0,
                locked = 2.0,
                avgPrice = 3.0,
                realizedPnl = 4.0,
                unrealizedPnl = 5.0
            )
        )

        store.save(rows)
        val loaded = store.load()

        assertEquals(rows, loaded)
    }
}
