package com.example.tradebot

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadBotSpecTest {
    @Test
    fun readBotSpecs_readsFromActiveBotsFile() {
        val jsonFile = File(com.example.platformutil.ACTIVE_BOTS_FILE_NAME)
        val originalContent = if (jsonFile.exists()) jsonFile.readText() else null
        val originalExisted = jsonFile.exists()
        jsonFile.writeText("""[{"name":"test-bot","cfg":{},"patterns":["seq=D0.D0"]}]""")

        try {
            val bots = readBotSpecs()
            assertEquals(1, bots.size)
            assertEquals("test-bot", bots.first().name)
        } finally {
            if (originalExisted) {
                jsonFile.writeText(originalContent ?: "")
            } else {
                jsonFile.delete()
            }
        }
    }

    @Test
    fun printBotRoster_handlesEmptyList() {
        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(buffer))
            printBotRoster(emptyList())
        } finally {
            System.setOut(originalOut)
        }
        val output = buffer.toString()
        assertTrue(output.contains("NO BOTS LOADED"))
    }
}
