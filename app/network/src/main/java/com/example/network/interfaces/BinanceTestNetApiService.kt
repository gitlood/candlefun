package com.example.network.interfaces

import com.example.network.BinanceTestNetApiServiceImpl
import com.example.network.model.TradeResponse
import java.io.File
import java.io.FileInputStream
import java.util.Properties

interface BinanceTestNetApiService {
    suspend fun createOrder(
        symbol: String,
        side: String,
        type: String,
        quantity: String,
        price: String? = null,
        timeInForce: String? = null
    ): TradeResponse

    companion object {
        fun create(): BinanceTestNetApiService {
            val properties = Properties()
            
            // Try current directory (project root usually)
            var file = File("local.properties")
            if (!file.exists()) {
                // Try parent directory
                file = File("../local.properties")
            }
            
            if (file.exists()) {
                try {
                    FileInputStream(file).use { properties.load(it) }
                } catch (e: Exception) {
                    System.err.println("Failed to read ${file.absolutePath}: ${e.message}")
                }
            }

            val apiKey = properties.getProperty("BINANCE_TEST_KEY")
                ?: System.getenv("BINANCE_TEST_KEY")
                ?: error("Missing BINANCE_TESTNET_API_KEY in local.properties or environment")

            val secret = properties.getProperty("BINANCE_TEST_SECRET")
                ?: System.getenv("BINANCE_TEST_SECRET")
                ?: error("Missing BINANCE_TESTNET_API_SECRET in local.properties or environment")

            return BinanceTestNetApiServiceImpl(apiKey = apiKey, secretKey = secret)
        }
    }
}
