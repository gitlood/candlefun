package com.example.execution.domain

interface AccountStateRepository {
    suspend fun getBalances(): List<BalanceSnapshot>
    suspend fun getFills(symbol: String, sinceMs: Long? = null): List<Fill>
}
