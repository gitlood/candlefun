package com.example.account.domain.inventory

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import kotlinx.coroutines.flow.Flow

interface InventoryStateRepository {
    fun streamInventory(): Flow<List<InventoryPosition>>
    suspend fun getInventory(): List<InventoryPosition>
    suspend fun applyFill(fill: InventoryFill)
    suspend fun applyPositionSnapshot(positions: List<InventoryPosition>)
    suspend fun applyMarkPrice(symbol: Symbol, markPrice: Price, timestampMs: Long)
    suspend fun persist()
}
