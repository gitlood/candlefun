package com.example.network.helper

import com.example.platform.model.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

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

/**
 * Helper to perform a DELETE request and return the body.
 * Assumes the HttpClient is configured with `expectSuccess = true`.
 */
suspend inline fun <reified T> HttpClient.deleteOrThrow(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): T {
    return delete(url, block).body()
}

/**
 * Helper to perform a GET request and return an ApiResult.
 * Does NOT throw on non-2xx responses (if HttpClient is configured to not throw, or we catch it).
 * Note: If HttpClient has `expectSuccess = true`, `get()` throws. We catch it here.
 */
suspend inline fun <reified T> HttpClient.getOrResult(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): ApiResult<T> {
    return try {
        val response = get(url, block)
        if (response.status.isSuccess()) {
            ApiResult.Ok(response.body())
        } else {
            ApiResult.Err(
                code = response.status.value,
                message = response.bodyAsText()
            )
        }
    } catch (e: Exception) {
        ApiResult.Err(message = e.message ?: "Unknown error", cause = e)
    }
}

/**
 * Helper to perform a POST request and return an ApiResult.
 */
suspend inline fun <reified T> HttpClient.postOrResult(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): ApiResult<T> {
    return try {
        val response = post(url, block)
        if (response.status.isSuccess()) {
            ApiResult.Ok(response.body())
        } else {
            ApiResult.Err(
                code = response.status.value,
                message = response.bodyAsText()
            )
        }
    } catch (e: Exception) {
        ApiResult.Err(message = e.message ?: "Unknown error", cause = e)
    }
}
