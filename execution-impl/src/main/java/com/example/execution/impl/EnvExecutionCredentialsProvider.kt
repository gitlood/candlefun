package com.example.execution.impl

import com.example.execution.domain.ExecutionCredentials
import com.example.execution.domain.ExecutionCredentialsProvider

class EnvExecutionCredentialsProvider : ExecutionCredentialsProvider {
    override fun testnet(): ExecutionCredentials {
        val apiKey = System.getenv("BINANCE_TESTNET_API_KEY")
        val secretKey = System.getenv("BINANCE_TESTNET_SECRET_KEY")
        require(!apiKey.isNullOrBlank()) { "BINANCE_TESTNET_API_KEY is required" }
        require(!secretKey.isNullOrBlank()) { "BINANCE_TESTNET_SECRET_KEY is required" }
        return ExecutionCredentials(apiKey, secretKey)
    }
}
