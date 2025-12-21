package com.example.network

import com.example.network.interfaces.BinanceApiService
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.BinanceTestNetApiService
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkModuleTest {
    @Test
    fun factoryMethods_returnImplementations() {
        assertTrue(BinanceApiService.create() is BinanceApiServiceImpl)
        assertTrue(BinanceOrderBookService.create() is BinanceOrderBookServiceImpl)
        assertTrue(BinanceTestNetApiServiceImpl::class.java.interfaces.contains(BinanceTestNetApiService::class.java))
    }
}
