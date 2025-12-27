package com.example.superbot

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private val STRATEGY_TASKS = listOf(
    ":avellaneda-mm:run",
    ":avellaneda-live:runTestnet",
    ":ofi-kukanov:run",
    ":ofi-kukanov:runTestnet",
    ":vacuum:runBacktest",
    ":vacuum:runTestnet",
    ":pairs:runBacktest",
    ":pairs:runTestnet",
    ":survivor:runBacktest",
    ":survivor:runTestnet",
    ":survivor:runTail"
)

fun main(args: Array<String>) {
    runBlocking {
        runSuperbot(args)
    }
}

private suspend fun runSuperbot(args: Array<String>) {
    val mode = args.firstOrNull()?.lowercase() ?: "short"
    val env = if (mode == "long") longEnv() else shortEnv()
    
    val currentDir = File(System.getProperty("user.dir")).canonicalFile
    println("superbot mode=$mode env=$env currentDir=$currentDir")

    val root = findProjectRoot(currentDir) ?: error("Could not find gradlew in $currentDir or parents")
    println("superbot project root=$root")

    coroutineScope {
        STRATEGY_TASKS.map { task ->
            launch {
                runTask(root, task, env)
            }
        }
    }
}

private fun findProjectRoot(start: File): File? {
    var curr: File? = start
    while (curr != null) {
        if (File(curr, "gradlew").exists()) {
            return curr
        }
        curr = curr.parentFile
    }
    return null
}

private fun shortEnv() = mapOf(
    "TELEMETRY_ENABLED" to "true",
    "TELEMETRY_DIR" to "reports/telemetry",
    "FAST_MODE" to "true",
    "LOG_KPI_EVERY_MS" to "10000",
    "LOG_EVERY_TICKS" to "500"
)

private fun longEnv() = mapOf(
    "TELEMETRY_ENABLED" to "true",
    "TELEMETRY_DIR" to "reports/telemetry",
    "LOG_KPI_EVERY_MS" to "60000",
    "LOG_EVERY_TICKS" to "1000"
)

private suspend fun runTask(root: File, task: String, env: Map<String, String>) {
    val name = task.replace(':', '_').trim('_')
    println("[superbot] starting $task")
    
    val process = ProcessBuilder("./gradlew", task)
        .directory(root)
        .apply { environment().putAll(env) }
        .redirectErrorStream(true)
        .start()

    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val readerJob = coroutineScope {
        launch {
            reader.useLines { lines ->
                lines.forEach { line ->
                    println("[$name] $line")
                }
            }
        }
    }
    val code = process.waitFor()
    readerJob.join()
    println("[$name] finished with exit $code")
}
