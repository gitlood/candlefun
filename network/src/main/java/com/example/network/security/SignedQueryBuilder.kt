package com.example.network.security

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class SignedQueryBuilder(
    private val signer: BinanceSigner,
    private val timestampProvider: TimestampProvider,
    private val defaultRecvWindow: String = "5000"
) {
    fun build(params: Map<String, String>): String {
        val mutable = params.toMutableMap()
        mutable["timestamp"] = timestampProvider.getTimestamp().toString()
        mutable.putIfAbsent("recvWindow", defaultRecvWindow)

        val query = mutable.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "${rfc3986Encode(k)}=${rfc3986Encode(v)}" }

        val sig = signer.sign(query)
        return "$query&signature=$sig"
    }

    private fun rfc3986Encode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
}
