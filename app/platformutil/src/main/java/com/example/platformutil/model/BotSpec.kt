package com.example.platformutil.model

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BINANCE_SYMBOL

data class TradingConfig(
    val symbol: String = BINANCE_SYMBOL,
    val quantity: String = "0.01",
    val maxOpenPositions: Int = 1,
    val mode: ExecutionMode = ExecutionMode.TESTNET,
)

enum class ExecutionMode { TESTNET, PAPER }

data class BotSpec(
    val name: String,
    val cfg: AlgoConfig,
    val patterns: Set<String>,
    val trade: TradingConfig = TradingConfig(),
)
