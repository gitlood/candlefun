package com.example.network

import com.example.network.client.client
import kotlin.test.Test
import kotlin.test.assertNotNull

class NetworkClientTest {
    @Test
    fun sharedClient_isAvailable() {
        assertNotNull(client)
    }
}
