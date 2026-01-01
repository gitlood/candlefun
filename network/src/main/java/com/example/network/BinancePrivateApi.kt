package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.helper.deleteOrThrow
import com.example.network.helper.getOrThrow
import com.example.network.helper.postOrThrow
import com.example.network.security.BinanceSigner
import com.example.network.security.SignedQueryBuilder
import com.example.network.security.TimestampProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header

internal class BinancePrivateApi(
    private val client: HttpClient,
    private val endpoints: BinanceEndpoints,
    private val signer: BinanceSigner,
    private val apiKey: String,
    private val timestampProvider: TimestampProvider
) {
    private val queryBuilder = SignedQueryBuilder(signer, timestampProvider)

    suspend inline fun <reified T> get(
        path: String,
        queryParams: Map<String, String> = emptyMap()
    ): T {
        val finalQuery = queryBuilder.build(queryParams)
        return client.getOrThrow("${endpoints.restBase}/$path?$finalQuery") {
            header("X-MBX-APIKEY", apiKey)
        }
    }

    suspend inline fun <reified T> post(
        path: String,
        queryParams: Map<String, String> = emptyMap()
    ): T {
        val finalQuery = queryBuilder.build(queryParams)
        return client.postOrThrow("${endpoints.restBase}/$path?$finalQuery") {
            header("X-MBX-APIKEY", apiKey)
        }
    }

    suspend inline fun <reified T> delete(
        path: String,
        queryParams: Map<String, String> = emptyMap()
    ): T {
        val finalQuery = queryBuilder.build(queryParams)
        return client.deleteOrThrow("${endpoints.restBase}/$path?$finalQuery") {
            header("X-MBX-APIKEY", apiKey)
        }
    }
}
