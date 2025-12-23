package com.example.network.helper

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post

/**
 * Helper to perform a GET request and return the body.
 * Assumes the HttpClient is configured with `expectSuccess = true`.
 */
suspend inline fun <reified T> HttpClient.getOrThrow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): T {
    return get(url, block).body()
}

/**
 * Helper to perform a POST request and return the body.
 * Assumes the HttpClient is configured with `expectSuccess = true`.
 */
suspend inline fun <reified T> HttpClient.postOrThrow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): T {
    return post(url, block).body()
}
