package com.example.platformutil.model

import com.example.platformutil.AlgoConfig

data class BotSpec(
    val name: String,
    val patterns: Set<String>,
    val cfg: AlgoConfig
)