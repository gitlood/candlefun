package com.example.platform.model

data class Account(
    val makerCommission: Int = 0,
    val takerCommission: Int = 0,
    val buyerCommission: Int = 0,
    val sellerCommission: Int = 0,
    val canTrade: Boolean = false,
    val canWithdraw: Boolean = false,
    val canDeposit: Boolean = false,
    val updateTime: Long = 0,
    val accountType: String = "",
    val balances: List<AccountBalance> = emptyList()
)

data class AccountBalance(
    val asset: String,
    val free: Double,
    val locked: Double
)
