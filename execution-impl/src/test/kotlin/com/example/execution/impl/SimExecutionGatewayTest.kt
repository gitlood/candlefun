package com.example.execution.impl

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.BalanceSnapshot
import com.example.execution.domain.Fill
import org.junit.Assert.assertEquals
import org.junit.Test

class SimExecutionGatewayTest {

    @Test
    fun `getPositions maps balances to positions`() {
        kotlinx.coroutines.runBlocking {
            val repo = object : AccountStateRepository {
                override suspend fun getBalances(): List<BalanceSnapshot> {
                    return listOf(
                        BalanceSnapshot(asset = "USDT", free = 5.0, locked = 1.0),
                        BalanceSnapshot(asset = "BTC", free = 0.2, locked = 0.1)
                    )
                }

                override suspend fun getFills(symbol: String, sinceMs: Long?): List<Fill> = emptyList()
            }
            val gateway = SimExecutionGateway(repo)

            val positions = gateway.getPositions()

            assertEquals(2, positions.size)
            assertEquals("USDT", positions[0].symbol)
            assertEquals(6.0, positions[0].quantity, 0.0)
        }
    }
}
