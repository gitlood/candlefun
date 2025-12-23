package com.example.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Balance(
    val asset: String = "",
    val free: String = "0",
    val locked: String = "0"
)
