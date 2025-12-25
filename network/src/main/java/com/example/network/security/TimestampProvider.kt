package com.example.network.security

import java.time.Instant

interface TimestampProvider {
    fun getTimestamp(): Long
}

class SystemTimestampProvider : TimestampProvider {
    override fun getTimestamp(): Long {
        return Instant.now().toEpochMilli()
    }
}
