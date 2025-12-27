package com.example.platform.report

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class TelemetryEvent(
    val type: String,
    val tsMs: Long,
    val data: Map<String, Any?> = emptyMap()
)

interface TelemetrySink {
    fun emit(event: TelemetryEvent)
    fun close() {}
}

object Telemetry {
    @Volatile
    private var sink: TelemetrySink = NoopTelemetrySink

    fun configure(next: TelemetrySink) {
        if (sink !== next) {
            sink.close()
            sink = next
        }
    }

    fun configureFromEnv(mode: String) {
        configure(TelemetrySinks.fromEnv(mode))
    }

    fun emit(type: String, tsMs: Long, data: Map<String, Any?> = emptyMap()) {
        sink.emit(TelemetryEvent(type, tsMs, data))
    }
}

object NoopTelemetrySink : TelemetrySink {
    override fun emit(event: TelemetryEvent) = Unit
}

class JsonlTelemetrySink(private val file: File) : TelemetrySink {
    private val writerRef = AtomicReference<BufferedWriter?>()
    private val lock = Any()

    override fun emit(event: TelemetryEvent) {
        val line = JsonEncoder.encode(
            mapOf(
                "type" to event.type,
                "ts_ms" to event.tsMs,
                "data" to event.data
            )
        )
        synchronized(lock) {
            val writer = ensureWriter()
            writer.append(line)
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            writerRef.getAndSet(null)?.close()
        }
    }

    private fun ensureWriter(): BufferedWriter {
        val existing = writerRef.get()
        if (existing != null) return existing
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val writer = BufferedWriter(FileWriter(file, true))
        writerRef.compareAndSet(null, writer)
        return writerRef.get() ?: writer
    }
}

object TelemetrySinks {
    fun fromEnv(mode: String): TelemetrySink {
        val enabled = System.getenv("TELEMETRY_ENABLED")?.toBooleanStrictOrNull() ?: false
        if (!enabled) return NoopTelemetrySink
        val truncate = System.getenv("TELEMETRY_TRUNCATE")?.toBooleanStrictOrNull() ?: false
        val timestamped = System.getenv("TELEMETRY_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
        val path = System.getenv("TELEMETRY_PATH")
            ?: run {
                val dir = System.getenv("TELEMETRY_DIR")
                if (!dir.isNullOrBlank()) {
                    File(dir, reportFileName(mode, timestamped)).absolutePath
                } else {
                    defaultReportPath(mode, timestamped)
                }
            }
        val file = File(path)
        if (truncate && file.exists()) {
            file.delete()
        }
        return JsonlTelemetrySink(file)
    }

    private fun defaultReportPath(mode: String, timestamped: Boolean): String {
        val root = findProjectRoot()
        val dir = File(root, "reports/telemetry")
        return File(dir, reportFileName(mode, timestamped)).absolutePath
    }

    private fun reportFileName(mode: String, timestamped: Boolean): String {
        if (!timestamped) return "telemetry_${mode}.jsonl"
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        return "telemetry_${mode}_$ts.jsonl"
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }
}

private object JsonEncoder {
    fun encode(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> value.toString()
            is Number -> number(value)
            is Map<*, *> -> encodeMap(value)
            is Iterable<*> -> encodeList(value)
            is Array<*> -> encodeList(value.asList())
            else -> quote(value.toString())
        }
    }

    private fun encodeMap(map: Map<*, *>): String {
        val parts = ArrayList<String>(map.size)
        for ((key, value) in map) {
            val k = key?.toString() ?: continue
            parts.add("${quote(k)}:${encode(value)}")
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun encodeList(list: Iterable<*>): String {
        val parts = list.map { encode(it) }
        return "[${parts.joinToString(",")}]"
    }

    private fun number(value: Number): String {
        val dbl = value.toDouble()
        if (!dbl.isFinite()) return "null"
        return when (value) {
            is Float, is Double -> String.format(Locale.US, "%.10f", dbl).trimEnd('0').trimEnd('.')
            else -> value.toString()
        }
    }

    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
