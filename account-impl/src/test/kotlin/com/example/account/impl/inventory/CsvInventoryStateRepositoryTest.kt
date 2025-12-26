package com.example.account.impl.inventory

import com.example.account.domain.Money
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.domain.inventory.InventoryPosition
import com.example.account.impl.config.InventoryWalletConfig
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvInventoryStateRepositoryTest {
    @Test
    fun `apply fill updates quantity avg and realized pnl`() = runBlocking {
        val temp = Files.createTempFile("inventory", ".csv").toFile()
        temp.deleteOnExit()
        val store = CsvWalletStore(temp.absolutePath)
        val config = InventoryWalletConfig(walletCsvPath = temp.absolutePath, autoPersist = false)
        val repo = CsvInventoryStateRepository(store, config)

        repo.applyFill(
            InventoryFill(
                symbol = Symbol.of("BTCUSDT"),
                signedQty = Qty.fromDouble(1.0),
                price = Price.fromDouble(100.0),
                timestampMs = 1L
            )
        )
        repo.applyFill(
            InventoryFill(
                symbol = Symbol.of("BTCUSDT"),
                signedQty = Qty.fromDouble(1.0),
                price = Price.fromDouble(200.0),
                timestampMs = 2L
            )
        )
        repo.applyFill(
            InventoryFill(
                symbol = Symbol.of("BTCUSDT"),
                signedQty = Qty.fromDouble(-1.0),
                price = Price.fromDouble(180.0),
                timestampMs = 3L
            )
        )

        val position = repo.getInventory().single()
        assertEquals(Qty.fromDouble(1.0), position.quantity)
        assertEquals(Price.fromDouble(180.0), position.avgPrice)
        assertEquals(0, position.realizedPnl.value.compareTo(Money.fromDouble(30.0).value))
    }

    @Test
    fun `apply mark price updates unrealized pnl and persist writes`() = runBlocking {
        val temp = Files.createTempFile("inventory", ".csv").toFile()
        temp.deleteOnExit()
        val store = CsvWalletStore(temp.absolutePath)
        val config = InventoryWalletConfig(walletCsvPath = temp.absolutePath, autoPersist = false)
        val repo = CsvInventoryStateRepository(store, config)
        val symbol = Symbol.of("ETHUSDT")
        repo.applyPositionSnapshot(
            listOf(
                InventoryPosition(
                    symbol = symbol,
                    quantity = Qty.fromDouble(2.0),
                    avgPrice = Price.fromDouble(100.0),
                    realizedPnl = Money.ZERO,
                    unrealizedPnl = Money.ZERO,
                    free = Qty.fromDouble(2.0),
                    locked = Qty.ZERO
                )
            )
        )

        repo.applyMarkPrice(symbol, Price.fromDouble(110.0), 10L)
        repo.persist()

        val updated = repo.getInventory().single()
        assertEquals(0, updated.unrealizedPnl.value.compareTo(Money.fromDouble(20.0).value))
        assertEquals(1, store.load().size)
    }
}
