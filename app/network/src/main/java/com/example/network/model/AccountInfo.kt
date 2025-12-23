package com.example.network.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val makerCommission: Int = 0,
    val takerCommission: Int = 0,
    val buyerCommission: Int = 0,
    val sellerCommission: Int = 0,
    val canTrade: Boolean = false,
    val canWithdraw: Boolean = false,
    val canDeposit: Boolean = false,
    val updateTime: Long = 0,
    val accountType: String = "",
    val balances: List<Balance> = emptyList()
)

@Serializable
data class Balance(
    val asset: String = "",
    val free: String = "0",
    val locked: String = "0"
)
