package com.example.network.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceSigner(
    private val secretKey: String
) {
    fun sign(queryString: String): String {
        val sha256Hmac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(), "HmacSHA256")
        sha256Hmac.init(secretKeySpec)
        val hash = sha256Hmac.doFinal(queryString.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
