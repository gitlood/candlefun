package com.example.tradebot

import com.example.platformutil.ACTIVE_BOTS_FILE_NAME
import com.example.platformutil.model.BotSpec
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

fun readBotSpecs(): List<BotSpec> {
    val mapper = jacksonObjectMapper()
    mapper.registerModule(JavaTimeModule())
    val jsonFile = File(ACTIVE_BOTS_FILE_NAME)

    return if (jsonFile.exists()) {
        try {
            mapper.readValue(jsonFile)
        } catch (e: Exception) {
            println("Error reading bots.json: ${e.message}")
            emptyList()
        }
    } else {
        println("Warning: bots.json not found, using empty roster.")
        emptyList()
    }
}

fun printBotRoster(bots: List<BotSpec>) {
    if (bots.isEmpty()) {
        println(" [!] NO BOTS LOADED ")
        return
    }

    val border = "═".repeat(200)
    println("\n$border")
    println(" ACTIVE BOT ROSTER (Count: ${bots.size})")
    println(border)

    bots.forEach { bot ->
        println("Name        : ${bot.name}")
        println("Symbol      : ${bot.trade.symbol} | Mode: ${bot.trade.mode} | MaxPos: ${bot.trade.maxOpenPositions}")
        println("Patterns    : ${bot.patterns.joinToString(", ")}")
        println("TP / SL     : ${bot.cfg.backtest.takeProfit * 100}% / ${bot.cfg.backtest.stopLoss * 100}%")
        println("Lookback    : ${bot.cfg.backtest.lookbackMinutes} min | Horizon: ${bot.cfg.backtest.horizonMinutes} min")
        println("Volume ZMin : ${bot.cfg.signal.volumeZMin}")
        println("-".repeat(200))
    }
    println("$border\n")
}
