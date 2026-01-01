package com.example.account.domain

data class Position(
    val symbol: Symbol,
    val quantity: Qty,
    val averagePrice: Price
)

data class BalanceSnapshot(
    val asset: Asset,
    val free: Qty,
    val locked: Qty
)

data class Fill(
    val symbol: Symbol,
    val price: Price,
    val quantity: Qty,
    val fillTimeMs: Long,
    val isBuyerMaker: Boolean
)
