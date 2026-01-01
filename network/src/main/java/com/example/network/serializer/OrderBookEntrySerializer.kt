package com.example.network.serializer

import com.example.network.dto.OrderBookEntryDto
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

object OrderBookEntrySerializer : KSerializer<OrderBookEntryDto> {
    private val delegateSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: OrderBookEntryDto) {
        throw UnsupportedOperationException("Serialization not implemented")
    }

    override fun deserialize(decoder: Decoder): OrderBookEntryDto {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can be loaded only by Json")
        val array = input.decodeJsonElement().jsonArray
        
        if (array.size < 2) throw SerializationException("Invalid order book entry array size")

        return OrderBookEntryDto(
            price = array[0].jsonPrimitive.content,
            quantity = array[1].jsonPrimitive.content
        )
    }
}
