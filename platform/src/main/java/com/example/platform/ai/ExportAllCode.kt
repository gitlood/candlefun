package com.example.platform.ai

import java.io.File

fun main() {
    val rootPath = "/Users/thelood/AndroidStudioProjects/CandleFun"
    val outputPath = "$rootPath/all_code_dump.txt"

    val rootDir = File(rootPath)
    val outputFile = File(outputPath)

    if (!rootDir.exists()) {
        println("Root directory not found: $rootPath")
        return
    }

    val sb = StringBuilder()

    rootDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { it.path.contains("/build/") }
        .filterNot { it.name == "ExportAllCode.kt" }
        .sortedBy { it.path }
        .forEach { file ->
            val relativePath = file.absolutePath.substringAfter(rootPath + "/")
            sb.appendLine("// ------------------------------------------------------------------")
            sb.appendLine("// FILE: $relativePath")
            sb.appendLine("// ------------------------------------------------------------------")
            sb.appendLine()
            sb.appendLine(file.readText())
            sb.appendLine()
            sb.appendLine()
        }

    outputFile.writeText(sb.toString())
    println("Successfully dumped all Kotlin code to: $outputPath")
}
