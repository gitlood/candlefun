package com.example.network.serializer

import com.example.network.dto.KlineDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

object KlineSerializer : KSerializer<KlineDto> {
    private val delegateSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: KlineDto) {
        // Serialization not implemented as we only consume API data
        throw UnsupportedOperationException("Serialization not implemented")
    }

    override fun deserialize(decoder: Decoder): KlineDto {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can be loaded only by Json")
        val array = input.decodeJsonElement().jsonArray
        
        // Ensure array has enough elements
        if (array.size < 11) throw SerializationException("Invalid kline array size")

        return KlineDto(
            openTime = array[0].jsonPrimitive.long,
            open = array[1].jsonPrimitive.content,
            high = array[2].jsonPrimitive.content,
            low = array[3].jsonPrimitive.content,
            close = array[4].jsonPrimitive.content,
            volume = array[5].jsonPrimitive.content,
            closeTime = array[6].jsonPrimitive.long,
            quoteAssetVolume = array[7].jsonPrimitive.content,
            numberOfTrades = array[8].jsonPrimitive.int,
            takerBuyBaseAssetVolume = array[9].jsonPrimitive.content,
            takerBuyQuoteAssetVolume = array[10].jsonPrimitive.content,
            ignore = array.getOrNull(11)?.jsonPrimitive?.content ?: "0"
        )
    }
}
