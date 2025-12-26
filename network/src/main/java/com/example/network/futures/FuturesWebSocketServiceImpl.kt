package com.example.network.futures

import com.example.network.futures.config.FuturesEndpoints
import com.example.network.dto.WsEnvelopeDto
import com.example.network.futures.interfaces.FuturesWebSocketService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class FuturesWebSocketServiceImpl(
    private val client: HttpClient,
    private val endpoints: FuturesEndpoints
) : FuturesWebSocketService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
        val streamParam = streams.joinToString("/")
        val url = "${endpoints.wsBase}?streams=$streamParam"

        try {
            client.webSocket(url) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()

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
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw t
        }
    }

    override fun connectUserStream(listenKey: String): Flow<String> = flow {
        val userBase = endpoints.wsBase.replace("/stream", "/ws")
        val url = "$userBase/$listenKey"
        try {
            client.webSocket(url) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    emit(frame.readText())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw t
        }
    }
}
