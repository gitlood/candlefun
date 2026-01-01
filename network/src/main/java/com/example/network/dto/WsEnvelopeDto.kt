package com.example.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class WsEnvelopeDto(
    val stream: String? = null,
    val data: WsData
)
