package com.example.network

import com.example.network.helper.NetworkConstants.WS_BASE_URL
import com.example.network.interfaces.LiveKlineRepo
import com.example.network.model.LiveCandle
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiveKlineRepoImpl(
    private val httpClient: HttpClient
) : LiveKlineRepo {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun streamClosed1mCandles(
        symbols: List<String>,
        onClosedCandle: suspend (symbol: String, candle: LiveCandle) -> Unit
    ) {
        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_1m" }
        val url = "$WS_BASE_URL?streams=$streams"

        val wsClient = httpClient.config {
            install(WebSockets)
        }

        wsClient.webSocket(urlString = url) {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val env = try {
                    json.decodeFromString(WsEnvelope.serializer(), text)
                } catch (e: Exception) {
                    continue
                }
                
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

    @Serializable
    private data class WsEnvelope(val data: WsData)

    @Serializable
    private data class WsData(
        @SerialName("s") val symbol: String,
        @SerialName("k") val kline: WsKline
    )

    @Serializable
    private data class WsKline(
        @SerialName("t") val openTime: Long,
        @SerialName("o") val open: String,
        @SerialName("h") val high: String,
        @SerialName("l") val low: String,
        @SerialName("c") val close: String,
        @SerialName("x") val isClosed: Boolean
    )
}
