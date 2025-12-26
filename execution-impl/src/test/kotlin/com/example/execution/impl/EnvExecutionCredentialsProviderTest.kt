package com.example.execution.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EnvExecutionCredentialsProviderTest {

    @Test
    fun `testnet returns env credentials or throws`() {
        val provider = EnvExecutionCredentialsProvider()
        val apiKey = System.getenv("BINANCE_TESTNET_API_KEY")
        val secretKey = System.getenv("BINANCE_TESTNET_SECRET_KEY")

        if (apiKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            try {
                provider.testnet()
                fail("Expected IllegalArgumentException when env vars are missing")
            } catch (_: IllegalArgumentException) {
            }
        } else {
            val creds = provider.testnet()
            assertEquals(apiKey, creds.apiKey)
            assertEquals(secretKey, creds.secretKey)
        }
    }
}
