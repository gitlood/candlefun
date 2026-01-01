package com.example.avellaneda.ai

import java.io.File

fun main() {
    val rootPath = "/Users/thelood/AndroidStudioProjects/CandleFun"
    val sourcePath = "$rootPath/avellaneda-mm/src/main/java"
    val outputPath = "$rootPath/avellaneda_mm_dump.txt"

    val sourceDir = File(sourcePath)
    val outputFile = File(outputPath)

    if (!sourceDir.exists()) {
        println("Source directory not found: $sourcePath")
        return
    }

    val sb = StringBuilder()

    sourceDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.name != "ExportAvellanedaMmCode.kt" }
        .sortedBy { it.path }
        .forEach { file ->
            val relativePath = file.absolutePath.substringAfter("src/main/java/")
            sb.appendLine("// ------------------------------------------------------------------")
            sb.appendLine("// FILE: $relativePath")
            sb.appendLine("// ------------------------------------------------------------------")
            sb.appendLine()
            sb.appendLine(file.readText())
            sb.appendLine()
            sb.appendLine()
        }

    outputFile.writeText(sb.toString())
    println("Successfully dumped avellaneda-mm module to: $outputPath")
}
