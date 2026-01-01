package com.example.network

import com.example.network.security.BinanceSigner
import com.example.network.security.SignedQueryBuilder
import com.example.network.security.TimestampProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class BinancePrivateApiTest {

    @Test
    fun `buildSignedQuery adds timestamp, recvWindow and signature correctly`() {
        // Arrange
        val secretKey = "secret"
        val timestamp = 1600000000000L

        val signer = BinanceSigner(secretKey)
        val timestampProvider = object : TimestampProvider {
            override fun getTimestamp() = timestamp
        }

        val builder = SignedQueryBuilder(signer, timestampProvider)

        // Act
        // Pass unordered map to ensure it sorts
        val params = mapOf(
            "symbol" to "BTCUSDT",
            "side" to "BUY"
        )
        val signedQuery = builder.build(params)

        // Assert
        // 1. Should contain original params
        assertTrue(signedQuery.contains("symbol=BTCUSDT"))
        assertTrue(signedQuery.contains("side=BUY"))

        // 2. Should contain timestamp
        assertTrue(signedQuery.contains("timestamp=$timestamp"))

        // 3. Should contain recvWindow (default)
        assertTrue(signedQuery.contains("recvWindow=5000"))

        // 4. Should contain signature at the end
        assertTrue(signedQuery.contains("&signature="))

        // 5. Verify order: recvWindow, side, symbol, timestamp (alphabetical)
        // recvWindow=5000&side=BUY&symbol=BTCUSDT&timestamp=1600000000000
        // We can check the exact string prefix before signature
        val prefix = "recvWindow=5000&side=BUY&symbol=BTCUSDT&timestamp=$timestamp"
        assertTrue("Query should start with sorted params", signedQuery.startsWith(prefix))

        // 6. Verify signature is valid for that prefix
        val expectedSig = signer.sign(prefix)
        assertTrue(signedQuery.endsWith(expectedSig))
    }
}
