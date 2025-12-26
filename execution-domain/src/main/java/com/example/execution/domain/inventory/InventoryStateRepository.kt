package com.example.execution.domain.inventory

import kotlinx.coroutines.flow.Flow

interface InventoryStateRepository {
    fun streamInventory(): Flow<List<InventoryPosition>>
    suspend fun getInventory(): List<InventoryPosition>
    suspend fun applyFill(fill: InventoryFill)
    suspend fun applyPositionSnapshot(positions: List<InventoryPosition>)
    suspend fun persist()
}
