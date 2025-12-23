package com.example.liveklines

import com.example.network.client.client
import io.ktor.client.* 
import io.ktor.client.plugins.websocket.* 
import io.ktor.websocket.* 
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

suspend fun streamClosed1mCandles(
    symbols: List<String>,
    onClosedCandle: suspend (symbol: String, candle: LiveCandle) -> Unit
) {
    val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_1m" }
    val url = "wss://stream.binance.com:9443/stream?streams=$streams"

    val wsClient = HttpClient(client.engine) {
        install(WebSockets)
    }

    wsClient.webSocket(urlString = url) {
        for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val env = json.decodeFromString(WsEnvelope.serializer(), text)
            val symbol = env.data.symbol.uppercase()
            val k = env.data.kline
            if (!k.isClosed) continue

            val candle = LiveCandle(
                openTime = k.openTime,
                open = k.open.toDouble(),
                high = k.high.toDouble(),
                low = k.low.toDouble(),
                close = k.close.toDouble()
            )
            onClosedCandle(symbol, candle)
        }
    }
}

@Serializable private data class WsEnvelope(val data: WsData)
@Serializable private data class WsData(
    @SerialName("s") val symbol: String,
    @SerialName("k") val kline: WsKline
)
@Serializable private data class WsKline(
    @SerialName("t") val openTime: Long,
    @SerialName("o") val open: String,
    @SerialName("h") val high: String,
    @SerialName("l") val low: String,
    @SerialName("c") val close: String,
    @SerialName("x") val isClosed: Boolean
)

data class LiveCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)
