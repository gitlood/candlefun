package com.example.execution.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Properties

class EnvExecutionCredentialsProviderTest {

    @Test
    fun `testnet returns env credentials or throws`() {
        val provider = EnvExecutionCredentialsProvider()
        val local = loadLocalProperties()
        val apiKey = local.getProperty("BINANCE_TEST_KEY")
            ?: local.getProperty("BINANCE_TESTNET_API_KEY")
            ?: local.getProperty("BINANCE_KEY")
            ?: System.getenv("BINANCE_TEST_KEY")
            ?: System.getenv("BINANCE_TESTNET_API_KEY")
            ?: System.getenv("BINANCE_KEY")
        val secretKey = local.getProperty("BINANCE_TEST_SECRET")
            ?: local.getProperty("BINANCE_TESTNET_SECRET_KEY")
            ?: local.getProperty("BINANCE_SECRET_KEY")
            ?: System.getenv("BINANCE_TEST_SECRET")
            ?: System.getenv("BINANCE_TESTNET_SECRET_KEY")
            ?: System.getenv("BINANCE_SECRET_KEY")

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

    private fun loadLocalProperties(): Properties {
        val props = Properties()
        val file = findLocalProperties(File(System.getProperty("user.dir")))
        if (file == null) {
            return props
        }
        file.inputStream().use { stream ->
            props.load(stream)
        }
        return props
    }

    private fun findLocalProperties(startDir: File): File? {
        var dir: File? = startDir
        while (dir != null) {
            val candidate = File(dir, "local.properties")
            if (candidate.exists()) {
                return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}
