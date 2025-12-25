package com.example.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountInfoDto(
    val makerCommission: Int = 0,
    val takerCommission: Int = 0,
    val buyerCommission: Int = 0,
    val sellerCommission: Int = 0,
    val canTrade: Boolean = false,
    val canWithdraw: Boolean = false,
    val canDeposit: Boolean = false,
    val updateTime: Long = 0,
    val accountType: String = "",
    val balances: List<BalanceDto> = emptyList()
)

@Serializable
data class BalanceDto(
    val asset: String,
    val free: String,
    val locked: String
)
