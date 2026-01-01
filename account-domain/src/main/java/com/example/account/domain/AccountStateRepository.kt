package com.example.account.domain

interface AccountStateRepository {
    suspend fun getBalances(): List<BalanceSnapshot>
    suspend fun getFills(symbol: Symbol, sinceTimeMs: Long? = null): List<Fill>
}
