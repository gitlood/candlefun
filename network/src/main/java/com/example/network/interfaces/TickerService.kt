package com.example.network.interfaces

import com.example.platform.model.Ticker

interface TickerService {
    suspend fun getTickers24hr(): List<Ticker>
}
