package com.example.network.futures

import com.example.network.futures.config.FuturesEndpoints
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.helper.postOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import kotlinx.serialization.Serializable

internal class FuturesUserDataServiceImpl(
    private val client: HttpClient,
    private val endpoints: FuturesEndpoints,
    private val apiKey: String
) : FuturesUserDataService {

    @Serializable
    private data class ListenKeyDto(val listenKey: String)

    override suspend fun createListenKey(): String {
        val url = "${endpoints.restBase}/fapi/v1/listenKey"
        val dto: ListenKeyDto = client.postOrThrow(url) {
            header("X-MBX-APIKEY", apiKey)
        }
        return dto.listenKey
    }

    override suspend fun keepAliveListenKey(listenKey: String) {
        val url = "${endpoints.restBase}/fapi/v1/listenKey"
        client.put(url) {
            header("X-MBX-APIKEY", apiKey)
            url {
                parameters.append("listenKey", listenKey)
            }
        }
    }

    override suspend fun closeListenKey(listenKey: String) {
        val url = "${endpoints.restBase}/fapi/v1/listenKey"
        client.delete(url) {
            header("X-MBX-APIKEY", apiKey)
            url {
                parameters.append("listenKey", listenKey)
            }
        }
    }
}
