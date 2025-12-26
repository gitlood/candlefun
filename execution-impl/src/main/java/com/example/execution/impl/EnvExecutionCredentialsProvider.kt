package com.example.execution.impl

import com.example.execution.domain.ExecutionCredentials
import com.example.execution.domain.ExecutionCredentialsProvider
import java.io.File
import java.util.Properties

class EnvExecutionCredentialsProvider : ExecutionCredentialsProvider {
    override fun testnet(): ExecutionCredentials {
        val local = loadLocalProperties()
        val apiKey = local.getProperty("BINANCE_TEST_KEY")
            ?: local.getProperty("BINANCE_TESTNET_API_KEY")
            ?: local.getProperty("BINANCE_FUTURES_TESTNET_API_KEY")
            ?: local.getProperty("BINANCE_KEY")
            ?: System.getenv("BINANCE_TEST_KEY")
            ?: System.getenv("BINANCE_TESTNET_API_KEY")
            ?: System.getenv("BINANCE_FUTURES_TESTNET_API_KEY")
            ?: System.getenv("BINANCE_KEY")
        val secretKey = local.getProperty("BINANCE_TEST_SECRET")
            ?: local.getProperty("BINANCE_TESTNET_SECRET_KEY")
            ?: local.getProperty("BINANCE_FUTURES_TESTNET_SECRET_KEY")
            ?: local.getProperty("BINANCE_SECRET_KEY")
            ?: System.getenv("BINANCE_TEST_SECRET")
            ?: System.getenv("BINANCE_TESTNET_SECRET_KEY")
            ?: System.getenv("BINANCE_FUTURES_TESTNET_SECRET_KEY")
            ?: System.getenv("BINANCE_SECRET_KEY")
        require(!apiKey.isNullOrBlank()) {
            "BINANCE_TEST_KEY (or BINANCE_TESTNET_API_KEY/BINANCE_FUTURES_TESTNET_API_KEY/BINANCE_KEY) is required"
        }
        require(!secretKey.isNullOrBlank()) {
            "BINANCE_TEST_SECRET (or BINANCE_TESTNET_SECRET_KEY/BINANCE_FUTURES_TESTNET_SECRET_KEY/BINANCE_SECRET_KEY) is required"
        }
        return ExecutionCredentials(apiKey, secretKey)
    }

    private fun loadLocalProperties(): Properties {
        val props = Properties()
        val file = findLocalProperties(findProjectRoot())
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

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }
}
