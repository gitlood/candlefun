package com.example.execution.impl.inventory

import com.example.execution.domain.inventory.CsvWalletRow
import com.example.execution.domain.inventory.InventoryFill
import com.example.execution.domain.inventory.InventoryPosition
import com.example.execution.domain.inventory.InventoryStateRepository
import com.example.execution.impl.config.InventoryWalletConfig
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
            val idx = current.indexOfFirst { it.asset == fill.asset }
            val pos = if (idx >= 0) current[idx] else InventoryPosition(
                asset = fill.asset,
                quantity = 0.0,
                avgPrice = 0.0,
                realizedPnl = 0.0,
                unrealizedPnl = 0.0,
                free = 0.0,
                locked = 0.0
            )

            val newQty = pos.quantity + fill.signedQty
            val newAvgPrice = if (newQty == 0.0) 0.0 else weightedAvgPrice(pos, fill)

            val updated = pos.copy(
                quantity = newQty,
                avgPrice = newAvgPrice
            )

            if (idx >= 0) {
                current[idx] = updated
            } else {
                current.add(updated)
            }

            state.value = current
        }
    }

    override suspend fun applyPositionSnapshot(positions: List<InventoryPosition>) {
        mutex.withLock {
            val merged = state.value.associateBy { it.asset }.toMutableMap()
            positions.forEach { pos ->
                val existing = merged[pos.asset]
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
                merged[pos.asset] = next
            }
            state.value = merged.values.toList()
        }
    }

    override suspend fun persist() {
        mutex.withLock {
            val rows = state.value.map { p ->
                CsvWalletRow(
                    asset = p.asset,
                    free = p.free,
                    locked = p.locked,
                    avgPrice = p.avgPrice,
                    realizedPnl = p.realizedPnl,
                    unrealizedPnl = p.unrealizedPnl
                )
            }
            store.save(rows)
        }
    }

    private fun loadPositions(): List<InventoryPosition> {
        return store.load().map { row ->
            InventoryPosition(
                asset = row.asset,
                quantity = row.free + row.locked,
                avgPrice = row.avgPrice,
                realizedPnl = row.realizedPnl,
                unrealizedPnl = row.unrealizedPnl,
                free = row.free,
                locked = row.locked
            )
        }
    }

    private fun weightedAvgPrice(pos: InventoryPosition, fill: InventoryFill): Double {
        val existingQty = pos.quantity
        val newQty = existingQty + fill.signedQty
        if (newQty == 0.0) return 0.0
        val existingCost = pos.avgPrice * existingQty
        val fillCost = fill.price * fill.signedQty
        return (existingCost + fillCost) / newQty
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
