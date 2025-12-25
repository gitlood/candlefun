package com.example.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class OrderBookDto(
    val lastUpdateId: Long,
    val bids: List<OrderBookEntryDto>,
    val asks: List<OrderBookEntryDto>
)
