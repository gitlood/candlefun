package com.example.network

import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.model.AccountInfo
import com.example.network.model.TradeResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class BinanceTestNetApiServiceImpl(
    private val client: HttpClient = com.example.network.client.client,
    private val apiKey: String,
    private val secretKey: String
) : BinanceTestNetApiService {

    private val baseUrl = "https://testnet.binance.vision/api/v3"

    // Helper to sign the query string
    private fun sign(queryString: String, secret: String): String {
        val sha256Hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        sha256Hmac.init(secretKey)
        val hash = sha256Hmac.doFinal(queryString.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    override suspend fun createOrder(
        symbol: String,
        side: String,
        type: String,
        quantity: String,
        price: String?,
        timeInForce: String?
    ): TradeResponse {
        // Construct query parameters
        val timestamp = Instant.now().toEpochMilli()
        val params = mutableListOf<String>()
        params.add("symbol=$symbol")
        params.add("side=$side")
        params.add("type=$type")
        params.add("quantity=$quantity")
        if (price != null) params.add("price=$price")
        if (timeInForce != null) params.add("timeInForce=$timeInForce")
        params.add("timestamp=$timestamp")

        val queryString = params.joinToString("&")
        val signature = sign(queryString, secretKey)
        val finalQueryString = "$queryString&signature=$signature"

        val response = client.post("$baseUrl/order?$finalQueryString") {
            header("X-MBX-APIKEY", apiKey)
        }

        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }

        return response.body<TradeResponse>()
    }

    override suspend fun fetchAccountInfo(): AccountInfo {
        val timestamp = Instant.now().toEpochMilli()
        val queryString = "timestamp=$timestamp"
        val signature = sign(queryString, secretKey)
        val finalQueryString = "$queryString&signature=$signature"

        val response = client.get("$baseUrl/account?$finalQueryString") {
            header("X-MBX-APIKEY", apiKey)
        }

        if (!response.status.isSuccess()) {
            throw ClientRequestException(response, response.bodyAsText())
        }

        return response.body<AccountInfo>()
    }
}
