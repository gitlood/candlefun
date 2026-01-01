package com.example.platform.report

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class TelemetryEvent(
    val type: String,
    val tsMs: Long,
    val data: Map<String, Any?> = emptyMap(),
    val schemaVersion: Int = 1
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

    fun configureFromEnv(mode: String, defaultEnabled: Boolean = false) {
        configure(TelemetrySinks.fromEnv(mode, defaultEnabled))
    }

    fun resolveReportPathFromEnv(mode: String, defaultEnabled: Boolean = false): String? {
        return TelemetrySinks.resolvePathFromEnv(mode, defaultEnabled)
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
                "schema_version" to event.schemaVersion,
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

class LatestTelemetrySink(
    private val file: File,
    allowedTypes: Set<String>
) : TelemetrySink {
    private val lock = Any()
    private val types = allowedTypes
    private val latest = LinkedHashMap<String, TelemetryEvent>()
    override fun emit(event: TelemetryEvent) {
        if (event.type !in types) return
        val key = buildKey(event)
        synchronized(lock) {
            latest[key] = event
            writeSnapshotLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            writeSnapshotLocked()
        }
    }

    private fun buildKey(event: TelemetryEvent): String {
        val symbol = event.data["symbol"]?.toString()?.trim().orEmpty()
        if (symbol.isNotEmpty()) return "${event.type}::$symbol"
        val strategy = event.data["strategy_id"]?.toString()?.trim().orEmpty()
        if (strategy.isNotEmpty()) return "${event.type}::$strategy"
        return event.type
    }

    private fun writeSnapshotLocked() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val payload = mapOf(
            "updated_ts_ms" to System.currentTimeMillis(),
            "events" to latest.values.map { event ->
                mapOf(
                    "type" to event.type,
                    "ts_ms" to event.tsMs,
                    "schema_version" to event.schemaVersion,
                    "data" to event.data
                )
            }
        )
        val tmp = File.createTempFile(file.name, ".tmp", parent)
            .apply { writeText(JsonEncoder.encode(payload)) }
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: NoSuchFileException) {
            if (tmp.exists()) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

}

enum class DropPolicy {
    DROP_NEW,
    DROP_OLD
}

class AsyncTelemetrySink(
    private val delegate: TelemetrySink,
    private val maxQueueSize: Int,
    private val batchSize: Int,
    private val dropPolicy: DropPolicy
) : TelemetrySink {
    private val lock = Object()
    private val queue = ArrayDeque<TelemetryEvent>(maxQueueSize.coerceAtLeast(16))
    @Volatile
    private var closed = false
    private var dropped = 0L
    private val worker = Thread(::drainLoop).apply {
        isDaemon = true
        name = "telemetry-writer"
        start()
    }

    override fun emit(event: TelemetryEvent) {
        synchronized(lock) {
            if (closed) return
            if (maxQueueSize > 0 && queue.size >= maxQueueSize) {
                if (dropPolicy == DropPolicy.DROP_OLD && queue.isNotEmpty()) {
                    queue.removeFirst()
                    queue.addLast(event)
                } else {
                    dropped++
                }
                return
            }
            queue.addLast(event)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        worker.join(2_000L)
        if (dropped > 0) {
            delegate.emit(
                TelemetryEvent(
                    type = "telemetry_drop",
                    tsMs = System.currentTimeMillis(),
                    data = mapOf("dropped" to dropped)
                )
            )
        }
        delegate.close()
    }

    private fun drainLoop() {
        while (true) {
            val batch = ArrayList<TelemetryEvent>(batchSize.coerceAtLeast(1))
            synchronized(lock) {
                while (queue.isEmpty() && !closed) {
                    lock.wait(250L)
                }
                if (queue.isEmpty() && closed) return
                val take = if (batchSize <= 0) queue.size else minOf(batchSize, queue.size)
                repeat(take) { batch.add(queue.removeFirst()) }
            }
            for (event in batch) {
                delegate.emit(event)
            }
        }
    }
}

object TelemetrySinks {
    fun fromEnv(mode: String, defaultEnabled: Boolean = false): TelemetrySink {
        val enabled = resolveEnabled(defaultEnabled)
        if (!enabled) return NoopTelemetrySink
        val telemetryMode = resolveMode()
        val truncate = System.getenv("TELEMETRY_TRUNCATE")?.toBooleanStrictOrNull() ?: false
        val timestamped = System.getenv("TELEMETRY_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
        val path = resolvePath(mode, timestamped, telemetryMode == "latest")
        val file = File(path)
        if (resolveLogPath()) {
            println("ReportPath   : ${file.absolutePath}")
        }
        if (truncate && file.exists()) {
            file.delete()
        }
        val base = when (telemetryMode) {
            "latest" -> LatestTelemetrySink(file, resolveLatestTypes())
            else -> {
                val baseSink = JsonlTelemetrySink(file)
                val dedupeTypes = resolveDedupeTypes()
                if (dedupeTypes.isEmpty()) baseSink else DedupeTelemetrySink(baseSink, dedupeTypes)
            }
        }
        if (!resolveAsyncEnabled()) return base
        return AsyncTelemetrySink(
            delegate = base,
            maxQueueSize = resolveAsyncQueueSize(),
            batchSize = resolveAsyncBatchSize(),
            dropPolicy = resolveAsyncDropPolicy()
        )
    }

    fun resolvePathFromEnv(mode: String, defaultEnabled: Boolean = false): String? {
        val enabled = resolveEnabled(defaultEnabled)
        if (!enabled) return null
        val telemetryMode = resolveMode()
        val timestamped = System.getenv("TELEMETRY_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
        return resolvePath(mode, timestamped, telemetryMode == "latest")
    }

    private fun resolveEnabled(defaultEnabled: Boolean): Boolean {
        return System.getenv("TELEMETRY_ENABLED")?.toBooleanStrictOrNull() ?: defaultEnabled
    }

    private fun resolvePath(mode: String, timestamped: Boolean, latestMode: Boolean): String {
        val explicitPath = System.getenv("TELEMETRY_PATH")
        if (!explicitPath.isNullOrBlank()) return explicitPath
        val dir = System.getenv("TELEMETRY_DIR")
            ?: System.getenv("REPORT_DIR")
        if (!dir.isNullOrBlank()) {
            return File(dir, reportFileName(mode, timestamped, latestMode)).absolutePath
        }
        return defaultReportPath(mode, timestamped, latestMode)
    }

    private fun defaultReportPath(mode: String, timestamped: Boolean, latestMode: Boolean): String {
        val root = findProjectRoot()
        val dir = File(root, "reports/telemetry")
        return File(dir, reportFileName(mode, timestamped, latestMode)).absolutePath
    }

    private fun reportFileName(mode: String, timestamped: Boolean, latestMode: Boolean): String {
        val ext = if (latestMode) "json" else "jsonl"
        val base = if (latestMode) "telemetry_${mode}_latest" else "telemetry_${mode}"
        if (!timestamped) return "$base.$ext"
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        return "${base}_$ts.$ext"
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }

    private fun resolveDedupeTypes(): Set<String> {
        val raw = System.getenv("TELEMETRY_DEDUPE_TYPES") ?: "market_snapshot"
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun resolveLatestTypes(): Set<String> {
        val raw = System.getenv("TELEMETRY_LATEST_TYPES")
            ?: "kpi_snapshot,health_summary,config_snapshot,strategy_signal"
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun resolveMode(): String {
        val raw = System.getenv("TELEMETRY_MODE")?.trim()?.lowercase()
        return if (raw.isNullOrBlank()) "latest" else raw
    }

    private fun resolveLogPath(): Boolean {
        return System.getenv("TELEMETRY_LOG_PATH")?.toBooleanStrictOrNull() ?: true
    }

    private fun resolveAsyncEnabled(): Boolean {
        return System.getenv("TELEMETRY_ASYNC")?.toBooleanStrictOrNull() ?: true
    }

    private fun resolveAsyncQueueSize(): Int {
        return System.getenv("TELEMETRY_ASYNC_QUEUE")?.toIntOrNull() ?: 10_000
    }

    private fun resolveAsyncBatchSize(): Int {
        return System.getenv("TELEMETRY_ASYNC_BATCH")?.toIntOrNull() ?: 50
    }

    private fun resolveAsyncDropPolicy(): DropPolicy {
        val raw = System.getenv("TELEMETRY_DROP_POLICY")?.trim()?.lowercase()
        return when (raw) {
            "drop_old", "drop_oldest" -> DropPolicy.DROP_OLD
            else -> DropPolicy.DROP_NEW
        }
    }
}

class DedupeTelemetrySink(
    private val delegate: TelemetrySink,
    dedupeTypes: Set<String>
) : TelemetrySink {
    private val buffer = mutableMapOf<String, TelemetryEvent>()
    private val types = dedupeTypes.map { it.trim() }.filter { it.isNotBlank() }.toSet()

    override fun emit(event: TelemetryEvent) {
        if (event.type in types) {
            val symbolKey = event.data["symbol"]?.toString() ?: ""
            buffer["${event.type}::$symbolKey"] = event
            return
        }
        delegate.emit(event)
    }

    override fun close() {
        buffer.values.forEach { delegate.emit(it) }
        buffer.clear()
        delegate.close()
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
