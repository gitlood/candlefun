package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.dto.WsEnvelopeDto
import com.example.network.interfaces.BinanceWebSocketService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

internal class BinanceWebSocketServiceImpl(
    private val client: HttpClient,
    private val endpoints: BinanceEndpoints
) : BinanceWebSocketService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
        val streamParam = streams.joinToString("/")
        val url = "${endpoints.wsBase}?streams=$streamParam"

        try {
            client.webSocket(url) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()

                    // ✅ Only catch *decode* issues
                    val envelope = try {
                        json.decodeFromString(WsEnvelopeDto.serializer(), text)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: SerializationException) {
                        // Optional: log a short sample, then skip
                        // println("[WS] decode failed: ${se.message} sample=${text.take(200)}")
                        continue
                    }

                    // ✅ Do NOT wrap emit in try/catch, let cancellation propagate
                    emit(envelope)
                }
            }
        } catch (ce: CancellationException) {
            // Normal: flow collector cancelled (first()/timeout/etc.)
            throw ce
        } catch (t: Throwable) {
            // Real WS failure
            // println("[WS] connection failed: ${t.message}")
            // Optionally rethrow if you want callers to see FAIL instead of silent end
            throw t
        }
    }
}
