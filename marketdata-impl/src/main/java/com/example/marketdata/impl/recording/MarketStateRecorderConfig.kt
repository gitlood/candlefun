package com.example.marketdata.impl.recording

import java.io.File

data class MarketStateRecorderConfig(
    val outputPath: String = defaultPath()
) {
    companion object {
        fun default(): MarketStateRecorderConfig = MarketStateRecorderConfig()
    }
}

private fun defaultPath(): String {
    val env = System.getenv("MARKETSTATE_RECORD_PATH")
    if (!env.isNullOrBlank()) return env

    val root = findProjectRoot()
    return File(root, "marketstate.csv").absolutePath
}

private fun findProjectRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        val parent = dir.parentFile ?: return dir
        dir = parent
    }
}
