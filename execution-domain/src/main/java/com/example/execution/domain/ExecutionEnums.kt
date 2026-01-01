package com.example.execution.domain

enum class OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    EXPIRED,
    UNKNOWN;

    companion object {
        fun fromString(raw: String?): OrderStatus {
            return try {
                if (raw.isNullOrBlank()) UNKNOWN else valueOf(raw.uppercase())
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}

enum class TimeInForce {
    GTC,
    IOC,
    FOK,
    GTX,
    GTE_GTC,
    UNKNOWN;

    companion object {
        fun fromString(raw: String?): TimeInForce {
            return try {
                if (raw.isNullOrBlank()) UNKNOWN else valueOf(raw.uppercase())
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}
