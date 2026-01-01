package com.example.network.security

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceSignerTest {

    @Test
    fun `sign returns correct HMAC SHA256 signature`() {
        // Test vectors from Binance API documentation or standard HMAC examples
        val secretKey = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"
        val queryString = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559"
        
        val signer = BinanceSigner(secretKey)
        val signature = signer.sign(queryString)
        
        // Expected signature from Binance API docs example
        val expected = "c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"
        
        assertEquals(expected, signature)
    }

    @Test
    fun `sign works with empty query string`() {
        val secretKey = "secret"
        val signer = BinanceSigner(secretKey)
        val signature = signer.sign("")
        
        // HMAC-SHA256("", "secret")
        // echo -n "" | openssl dgst -sha256 -hmac "secret"
        val expected = "f9e66e179b6747ae54108f82f8ade8b3c25d76fd30afde6c395822c530196169"
        assertEquals(expected, signature)
    }
}
