package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal fun jsonMockEngine(handler: MockRequestHandler): MockEngine {
    return MockEngine(handler)
}

internal fun jsonMockClient(engine: MockEngine): HttpClient {
    return HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
}

internal fun emptyJsonClient(): HttpClient {
    return jsonMockClient(jsonMockEngine { respond("", HttpStatusCode.OK) })
}
