package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = FuturesUserDataSerializer::class)
sealed interface FuturesUserDataEvent {
    val eventType: String
    val eventTime: Long
}

@Serializable
data class FuturesOrderTradeUpdate(
    @SerialName("e") override val eventType: String,
    @SerialName("E") override val eventTime: Long,
    @SerialName("o") val order: FuturesOrderUpdate
) : FuturesUserDataEvent

@Serializable
data class FuturesAccountUpdate(
    @SerialName("e") override val eventType: String,
    @SerialName("E") override val eventTime: Long,
    @SerialName("a") val account: FuturesAccountUpdatePayload
) : FuturesUserDataEvent

@Serializable
data class FuturesOrderUpdate(
    @SerialName("s") val symbol: String,
    @SerialName("S") val side: String,
    @SerialName("o") val orderType: String,
    @SerialName("X") val orderStatus: String,
    @SerialName("p") val price: String,
    @SerialName("q") val origQty: String,
    @SerialName("z") val cumQty: String,
    @SerialName("l") val lastFilledQty: String,
    @SerialName("L") val lastFilledPrice: String,
    @SerialName("T") val tradeTime: Long
)

@Serializable
data class FuturesAccountUpdatePayload(
    @SerialName("B") val balances: List<FuturesBalanceUpdate> = emptyList(),
    @SerialName("P") val positions: List<FuturesPositionUpdate> = emptyList()
)

@Serializable
data class FuturesBalanceUpdate(
    @SerialName("a") val asset: String,
    @SerialName("wb") val walletBalance: String,
    @SerialName("cw") val crossWalletBalance: String
)

@Serializable
data class FuturesPositionUpdate(
    @SerialName("s") val symbol: String,
    @SerialName("pa") val positionAmt: String,
    @SerialName("ep") val entryPrice: String,
    @SerialName("cr") val accumulatedRealized: String,
    @SerialName("up") val unrealizedPnl: String
)

object FuturesUserDataSerializer : JsonContentPolymorphicSerializer<FuturesUserDataEvent>(FuturesUserDataEvent::class) {
    override fun selectDeserializer(element: JsonElement) = run {
        val obj = element.jsonObject
        when (obj["e"]?.jsonPrimitive?.contentOrNull) {
            "ORDER_TRADE_UPDATE" -> FuturesOrderTradeUpdate.serializer()
            "ACCOUNT_UPDATE" -> FuturesAccountUpdate.serializer()
            else -> FuturesOrderTradeUpdate.serializer()
        }
    }
}
