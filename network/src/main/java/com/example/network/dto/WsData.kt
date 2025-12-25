package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = WsDataSerializer::class)
sealed interface WsData {
    val symbol: String
}

@Serializable
data class WsKlineData(
    @SerialName("s") override val symbol: String,
    @SerialName("k") val kline: WsKlineDto
) : WsData

@Serializable
data class WsBookTickerData(
    @SerialName("s") override val symbol: String,
    @SerialName("E") val eventTime: Long? = null,
    @SerialName("u") val updateId: Long? = null,
    @SerialName("b") val bestBidPrice: String,
    @SerialName("B") val bestBidQty: String,
    @SerialName("a") val bestAskPrice: String,
    @SerialName("A") val bestAskQty: String
) : WsData

@Serializable
data class WsDepthUpdateData(
    @SerialName("s") override val symbol: String,
    @SerialName("E") val eventTime: Long? = null,
    @SerialName("U") val firstUpdateId: Long,
    @SerialName("u") val finalUpdateId: Long,
    @SerialName("b") val bids: List<List<String>>,
    @SerialName("a") val asks: List<List<String>>
) : WsData

@Serializable
data class WsAggTradeData(
    @SerialName("s") override val symbol: String,
    @SerialName("E") val eventTime: Long? = null,
    @SerialName("a") val aggTradeId: Long,
    @SerialName("p") val price: String,
    @SerialName("q") val quantity: String,
    @SerialName("f") val firstTradeId: Long,
    @SerialName("l") val lastTradeId: Long,
    @SerialName("T") val tradeTime: Long,
    @SerialName("m") val isBuyerMaker: Boolean
) : WsData

@Serializable
data class WsUnknownData(
    override val symbol: String = ""
) : WsData

object WsDataSerializer : JsonContentPolymorphicSerializer<WsData>(WsData::class) {
    override fun selectDeserializer(element: JsonElement) = run {
        val obj = element.jsonObject

        when {
            obj["e"]?.jsonPrimitive?.contentOrNull == "aggTrade" -> WsAggTradeData.serializer()

            // kline payload has "k"
            "k" in obj -> WsKlineData.serializer()

            // bookTicker has b/B/a/A
            "b" in obj && "B" in obj && "a" in obj && "A" in obj -> WsBookTickerData.serializer()

            // depthUpdate has U/u and b/a arrays
            "U" in obj && "u" in obj && "b" in obj && "a" in obj -> WsDepthUpdateData.serializer()

            else -> WsUnknownData.serializer()
        }
    }
}
