package com.example.execution.domain

data class ExecutionCredentials(
    val apiKey: String,
    val secretKey: String
)

interface ExecutionCredentialsProvider {
    fun testnet(): ExecutionCredentials
}
