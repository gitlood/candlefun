package com.example.network

import com.example.network.dto.AccountInfoDto
import com.example.network.dto.TradeResponseDto
import com.example.network.helper.NetworkConstants.TESTNET_BASE_URL
import com.example.network.helper.getOrThrow
import com.example.network.helper.postOrThrow
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.model.Account
import com.example.network.model.AccountBalance
import com.example.network.model.OrderResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class BinanceTestNetApiServiceImpl(
    private val client: HttpClient,
    private val apiKey: String,
    private val secretKey: String
) : BinanceTestNetApiService {

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
    ): OrderResponse {
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

        val dto = client.postOrThrow<TradeResponseDto>("$TESTNET_BASE_URL/order?$finalQueryString") {
            header("X-MBX-APIKEY", apiKey)
        }

        return OrderResponse(
            symbol = dto.symbol,
            orderId = dto.orderId,
            clientOrderId = dto.clientOrderId,
            transactTime = dto.transactTime,
            price = dto.price.toDoubleOrNull() ?: 0.0,
            origQty = dto.origQty.toDoubleOrNull() ?: 0.0,
            executedQty = dto.executedQty.toDoubleOrNull() ?: 0.0,
            cummulativeQuoteQty = dto.cummulativeQuoteQty.toDoubleOrNull() ?: 0.0,
            status = dto.status,
            timeInForce = dto.timeInForce,
            type = dto.type,
            side = dto.side
        )
    }

    override suspend fun fetchAccountInfo(): Account {
        val timestamp = Instant.now().toEpochMilli()
        val queryString = "timestamp=$timestamp"
        val signature = sign(queryString, secretKey)
        val finalQueryString = "$queryString&signature=$signature"

        val dto = client.getOrThrow<AccountInfoDto>("$TESTNET_BASE_URL/account?$finalQueryString") {
            header("X-MBX-APIKEY", apiKey)
        }

        return Account(
            makerCommission = dto.makerCommission,
            takerCommission = dto.takerCommission,
            buyerCommission = dto.buyerCommission,
            sellerCommission = dto.sellerCommission,
            canTrade = dto.canTrade,
            canWithdraw = dto.canWithdraw,
            canDeposit = dto.canDeposit,
            updateTime = dto.updateTime,
            accountType = dto.accountType,
            balances = dto.balances.map {
                AccountBalance(
                    asset = it.asset,
                    free = it.free.toDoubleOrNull() ?: 0.0,
                    locked = it.locked.toDoubleOrNull() ?: 0.0
                )
            }
        )
    }
}
