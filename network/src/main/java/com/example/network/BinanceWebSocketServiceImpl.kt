package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.dto.WsEnvelopeDto
import com.example.network.interfaces.BinanceWebSocketService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.isActive

internal class BinanceWebSocketServiceImpl(
    private val client: HttpClient,
    private val endpoints: BinanceEndpoints
) : BinanceWebSocketService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
        val streamParam = streams.joinToString("/")
        val url = "${endpoints.wsBase}?streams=$streamParam"

        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(url) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()

                        // Only catch decode issues.
                        val envelope = try {
                            json.decodeFromString(WsEnvelopeDto.serializer(), text)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: SerializationException) {
                            continue
                        }

                        emit(envelope)
                    }
                }
                attempt = 0
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                attempt++
                delay(reconnectDelayMs(attempt))
            }
        }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val capped = attempt.coerceAtMost(6)
        return 500L * (1 shl capped)
    }
}
