package com.example.account.domain

import java.math.BigDecimal

@JvmInline
value class Symbol(val value: String) {
    init {
        require(value.isNotBlank()) { "symbol cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun of(raw: String): Symbol = Symbol(raw.uppercase())
    }
}

@JvmInline
value class Asset(val value: String) {
    init {
        require(value.isNotBlank()) { "asset cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun of(raw: String): Asset = Asset(raw.uppercase())
    }
}

@JvmInline
value class Price(val value: BigDecimal) : Comparable<Price> {
    override fun compareTo(other: Price): Int = value.compareTo(other.value)

    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO = Price(BigDecimal.ZERO)
        fun fromString(raw: String): Price = Price(BigDecimal(raw))
        fun fromDouble(raw: Double): Price = Price(BigDecimal.valueOf(raw))
    }
}

@JvmInline
value class Qty(val value: BigDecimal) : Comparable<Qty> {
    override fun compareTo(other: Qty): Int = value.compareTo(other.value)

    operator fun plus(other: Qty): Qty = Qty(value.add(other.value))
    operator fun minus(other: Qty): Qty = Qty(value.subtract(other.value))
    operator fun unaryMinus(): Qty = Qty(value.negate())

    fun isZero(): Boolean = value.compareTo(BigDecimal.ZERO) == 0

    fun toDouble(): Double = value.toDouble()

    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO = Qty(BigDecimal.ZERO)
        fun fromString(raw: String): Qty = Qty(BigDecimal(raw))
        fun fromDouble(raw: Double): Qty = Qty(BigDecimal.valueOf(raw))
    }
}

@JvmInline
value class Money(val value: BigDecimal) : Comparable<Money> {
    override fun compareTo(other: Money): Int = value.compareTo(other.value)

    operator fun plus(other: Money): Money = Money(value.add(other.value))
    operator fun minus(other: Money): Money = Money(value.subtract(other.value))

    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO = Money(BigDecimal.ZERO)
        fun fromString(raw: String): Money = Money(BigDecimal(raw))
        fun fromDouble(raw: Double): Money = Money(BigDecimal.valueOf(raw))
    }
}
