package com.example.account.impl.inventory

import com.example.account.domain.Money
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.CsvWalletRow
import com.example.account.domain.inventory.InventoryFill
import com.example.account.domain.inventory.InventoryPosition
import com.example.account.domain.inventory.InventoryStateRepository
import com.example.account.impl.config.InventoryWalletConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CsvInventoryStateRepository(
    private val store: CsvWalletStore,
    private val config: InventoryWalletConfig = InventoryWalletConfig.default(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : InventoryStateRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(loadPositions())
    private var persistJob: Job? = null

    init {
        if (config.autoPersist) startAutoPersist()
    }

    override fun streamInventory(): Flow<List<InventoryPosition>> = state.asStateFlow()

    override suspend fun getInventory(): List<InventoryPosition> = state.value

    override suspend fun applyFill(fill: InventoryFill) {
        mutex.withLock {
            val current = state.value.toMutableList()
            val idx = current.indexOfFirst { it.symbol == fill.symbol }
            val pos = if (idx >= 0) current[idx] else InventoryPosition(
                symbol = fill.symbol,
                quantity = Qty.ZERO,
                avgPrice = Price.ZERO,
                realizedPnl = Money.ZERO,
                unrealizedPnl = Money.ZERO,
                free = Qty.ZERO,
                locked = Qty.ZERO
            )

            val updated = applyFillToPosition(pos, fill)
            val updatedWithFee = if (fill.fee == Money.ZERO) {
                updated
            } else {
                updated.copy(realizedPnl = Money(updated.realizedPnl.value.subtract(fill.fee.value)))
            }

            if (idx >= 0) {
                current[idx] = updatedWithFee
            } else {
                current.add(updatedWithFee)
            }

            state.value = current
        }
    }

    override suspend fun applyPositionSnapshot(positions: List<InventoryPosition>) {
        mutex.withLock {
            val merged = state.value.associateBy { it.symbol }.toMutableMap()
            positions.forEach { pos ->
                val existing = merged[pos.symbol]
                val next = if (existing == null) {
                    pos
                } else {
                    existing.copy(
                        quantity = pos.quantity,
                        avgPrice = pos.avgPrice,
                        realizedPnl = pos.realizedPnl,
                        unrealizedPnl = pos.unrealizedPnl
                    )
                }
                merged[pos.symbol] = next
            }
            state.value = merged.values.toList()
        }
    }

    override suspend fun applyMarkPrice(symbol: Symbol, markPrice: Price, timestampMs: Long) {
        mutex.withLock {
            val current = state.value.toMutableList()
            val idx = current.indexOfFirst { it.symbol == symbol }
            if (idx < 0) return
            val pos = current[idx]
            val unrealized = markPrice.value.subtract(pos.avgPrice.value).multiply(pos.quantity.value)
            current[idx] = pos.copy(unrealizedPnl = Money(unrealized))
            state.value = current
        }
    }

    override suspend fun persist() {
        mutex.withLock {
            val rows = state.value.map { p ->
                CsvWalletRow(
                    asset = p.symbol.value,
                    free = p.free.value.toDouble(),
                    locked = p.locked.value.toDouble(),
                    avgPrice = p.avgPrice.value.toDouble(),
                    realizedPnl = p.realizedPnl.value.toDouble(),
                    unrealizedPnl = p.unrealizedPnl.value.toDouble()
                )
            }
            store.save(rows)
        }
    }

    private fun loadPositions(): List<InventoryPosition> {
        return store.load().map { row ->
            InventoryPosition(
                symbol = Symbol.of(row.asset),
                quantity = Qty.fromDouble(row.free + row.locked),
                avgPrice = Price.fromDouble(row.avgPrice),
                realizedPnl = Money.fromDouble(row.realizedPnl),
                unrealizedPnl = Money.fromDouble(row.unrealizedPnl),
                free = Qty.fromDouble(row.free),
                locked = Qty.fromDouble(row.locked)
            )
        }
    }

    private fun applyFillToPosition(pos: InventoryPosition, fill: InventoryFill): InventoryPosition {
        val existingQty = pos.quantity
        val fillQty = fill.signedQty
        val newQty = existingQty + fillQty

        if (existingQty.isZero()) {
            return pos.copy(
                quantity = newQty,
                avgPrice = fill.price
            )
        }

        val sameSide = existingQty.value.multiply(fillQty.value).signum() > 0
        if (sameSide) {
            val newAvg = weightedAvg(existingQty, pos.avgPrice, fillQty, fill.price)
            return pos.copy(
                quantity = newQty,
                avgPrice = newAvg
            )
        }

        val closedQty = if (existingQty.value.abs() <= fillQty.value.abs()) {
            Qty(existingQty.value.abs())
        } else {
            Qty(fillQty.value.abs())
        }
        val realizedDelta = if (existingQty.value.signum() > 0) {
            fill.price.value.subtract(pos.avgPrice.value).multiply(closedQty.value)
        } else {
            pos.avgPrice.value.subtract(fill.price.value).multiply(closedQty.value)
        }

        val remainingQty = newQty
        val newAvg = if (remainingQty.isZero()) Price.ZERO else fill.price

        return pos.copy(
            quantity = remainingQty,
            avgPrice = newAvg,
            realizedPnl = Money(pos.realizedPnl.value.add(realizedDelta))
        )
    }

    private fun weightedAvg(q1: Qty, p1: Price, q2: Qty, p2: Price): Price {
        val total = q1 + q2
        if (total.isZero()) return Price.ZERO
        val cost = p1.value.multiply(q1.value).add(p2.value.multiply(q2.value))
        return Price(cost.divide(total.value, java.math.MathContext.DECIMAL128))
    }

    private fun startAutoPersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            while (true) {
                delay(config.persistEveryMs)
                persist()
            }
        }
    }
}
