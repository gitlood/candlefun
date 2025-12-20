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

    val border = "═".repeat(160)
    println("\n$border")
    println(" ACTIVE BOT ROSTER (Count: ${bots.size})")
    println(border)
    // Table Header
    println("%-85s | %-30s | %-8s | %-8s | %-8s | %-8s".format(
        "Name", "Patterns", "TP%", "SL%", "Look(m)", "Vol-Z"
    ))
    println("-".repeat(160))

    bots.forEach { bot ->
        // Format patterns as a comma-separated string if there are multiple
        val patternsDisplay = bot.patterns.joinToString(", ").let {
            if (it.length > 30) it.take(27) + "..." else it
        }

        println("%-85s | %-30s | %-8.2f%% | %-8.2f%% | %-8d | %-8.2f".format(
            bot.name.take(85),
            patternsDisplay,
            bot.cfg.backtest.takeProfit * 100,
            bot.cfg.backtest.stopLoss * 100,
            bot.cfg.backtest.lookbackMinutes,
            bot.cfg.signal.volumeZMin
        ))
    }
    println("$border\n")
}
