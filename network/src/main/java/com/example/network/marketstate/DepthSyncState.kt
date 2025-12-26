package com.example.network.marketstate

import com.example.network.dto.WsDepthUpdateData
import com.example.platform.model.OrderBook

internal class DepthSyncState {
    private val bufferedUpdates = ArrayList<WsDepthUpdateData>()
    private var pendingSnapshot: OrderBook? = null

    var isSyncing: Boolean = false
        private set

    fun start() {
        isSyncing = true
        bufferedUpdates.clear()
        pendingSnapshot = null
    }

    fun stop() {
        isSyncing = false
        bufferedUpdates.clear()
        pendingSnapshot = null
    }

    fun buffer(update: WsDepthUpdateData) {
        bufferedUpdates.add(update)
        if (bufferedUpdates.size > MAX_BUFFERED_UPDATES) {
            bufferedUpdates.removeAt(0)
        }
    }

    fun setSnapshot(snapshot: OrderBook) {
        pendingSnapshot = snapshot
    }

    fun tryApply(
        orderBook: OrderBookTracker,
        onApplied: (WsDepthUpdateData, OrderBookUpdateResult) -> Unit
    ): DepthSyncResult {
        if (!isSyncing) return DepthSyncResult.PENDING
        val snapshot = pendingSnapshot ?: return DepthSyncResult.PENDING

        val snapId = snapshot.lastUpdateId
        val target = snapId + 1
        var bridgeIndex = -1

        for (i in bufferedUpdates.indices) {
            val u = bufferedUpdates[i]
            if (u.finalUpdateId <= snapId) continue
            if (u.firstUpdateId <= target && u.finalUpdateId >= target) {
                bridgeIndex = i
                break
            }
            if (u.firstUpdateId > target) {
                stop()
                return DepthSyncResult.MISSED_BRIDGE
            }
        }

        if (bridgeIndex == -1) return DepthSyncResult.PENDING

        orderBook.loadSnapshot(snapshot)

        val bridgeUpdate = bufferedUpdates[bridgeIndex]
        val bridgeResult = orderBook.applyUpdate(bridgeUpdate)
        if (!bridgeResult.ok) {
            stop()
            return DepthSyncResult.FAILED
        }
        onApplied(bridgeUpdate, bridgeResult)

        for (i in bridgeIndex + 1 until bufferedUpdates.size) {
            val u = bufferedUpdates[i]
            if (u.finalUpdateId <= orderBook.lastUpdateId) continue
            val res = orderBook.applyUpdate(u)
            if (!res.ok) {
                stop()
                return DepthSyncResult.FAILED
            }
            onApplied(u, res)
        }

        stop()
        return DepthSyncResult.APPLIED
    }

    companion object {
        private const val MAX_BUFFERED_UPDATES = 2048
    }
}

internal enum class DepthSyncResult {
    APPLIED,
    PENDING,
    MISSED_BRIDGE,
    FAILED
}
