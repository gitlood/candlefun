package com.example.network.dto

import com.example.network.serializer.OrderBookEntrySerializer
import kotlinx.serialization.Serializable

@Serializable(with = OrderBookEntrySerializer::class)
data class OrderBookEntryDto(
    val price: String,
    val quantity: String
)
