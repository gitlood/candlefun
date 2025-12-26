package com.example.execution.impl.inventory

import com.example.execution.domain.inventory.CsvWalletRow

import java.io.File

class CsvWalletStore(
    private val filePath: String
) {
    fun load(): List<CsvWalletRow> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 6) return@mapNotNull null
            CsvWalletRow(
                asset = parts[0].trim(),
                free = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
                locked = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                avgPrice = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                realizedPnl = parts[4].toDoubleOrNull() ?: return@mapNotNull null,
                unrealizedPnl = parts[5].toDoubleOrNull() ?: return@mapNotNull null
            )
        }
    }

    fun save(rows: List<CsvWalletRow>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val header = "asset,free,locked,avgPrice,realizedPnl,unrealizedPnl"
        val body = rows.joinToString("\n") { r ->
            listOf(
                r.asset,
                r.free,
                r.locked,
                r.avgPrice,
                r.realizedPnl,
                r.unrealizedPnl
            ).joinToString(",")
        }
        file.writeText(header + "\n" + body)
    }
}
