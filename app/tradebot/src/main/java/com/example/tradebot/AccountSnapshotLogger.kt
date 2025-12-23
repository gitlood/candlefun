package com.example.tradebot

import com.example.network.model.AccountInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AccountSnapshotLogger(
    path: String,
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
) {

    private val file = File(path)
    private val initialized = AtomicBoolean(false)

    init {
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    }

    fun log(snapshot: AccountInfo, startingCapitalUsd: Double?) {
        if (initialized.compareAndSet(false, true)) {
            file.appendText("timestamp,asset,free,locked,total,startingCapital\n")
        }

        val timestamp = dateFormat.format(System.currentTimeMillis())
        val startCap = startingCapitalUsd?.let { "%.2f".format(it) } ?: ""

        val rows = snapshot.balances.map { balance ->
            val free = balance.free
            val locked = balance.locked
            val total = formatBalanceSum(free, locked)
            buildString {
                append(escape(timestamp))
                append(',')
                append(escape(balance.asset))
                append(',')
                append(escape(free))
                append(',')
                append(escape(locked))
                append(',')
                append(escape(total))
                append(',')
                append(escape(startCap))
            }
        }

        synchronized(this) {
            rows.forEach { file.appendText(it); file.appendText("\n") }
        }
    }

    private fun formatBalanceSum(free: String, locked: String): String {
        val freeVal = free.toDoubleOrNull() ?: 0.0
        val lockedVal = locked.toDoubleOrNull() ?: 0.0
        return "%.8f".format(freeVal + lockedVal)
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
