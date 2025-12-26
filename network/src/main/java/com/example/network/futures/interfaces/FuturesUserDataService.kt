package com.example.network.futures.interfaces

interface FuturesUserDataService {
    suspend fun createListenKey(): String
    suspend fun keepAliveListenKey(listenKey: String)
    suspend fun closeListenKey(listenKey: String)
}
