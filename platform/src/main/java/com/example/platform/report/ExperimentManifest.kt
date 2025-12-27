package com.example.platform.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
data class ExperimentManifest(
    val timestampMs: Long,
    val strategy: String,
    val mode: String,
    val symbols: List<String>,
    val params: Map<String, String> = emptyMap(),
    val reportPath: String? = null,
    val runId: String? = null,
    val notes: String? = null
)

class ExperimentManifestWriter private constructor(
    private val file: File
) {
    fun write(manifest: ExperimentManifest) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        FileWriter(file, true).use { writer ->
            writer.appendLine(JSON.encodeToString(manifest))
        }
    }

    fun path(): String = file.absolutePath

    companion object {
        private val JSON = Json { encodeDefaults = true }
        private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)

        fun fromEnv(): ExperimentManifestWriter? {
            val enabled = System.getenv("MANIFEST_ENABLED")?.toBooleanStrictOrNull() ?: true
            if (!enabled) return null
            val timestamped = System.getenv("MANIFEST_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
            val path = System.getenv("MANIFEST_PATH")
                ?: run {
                    val dir = System.getenv("REPORT_DIR")
                    if (!dir.isNullOrBlank()) {
                        File(dir, manifestFileName(timestamped)).absolutePath
                    } else {
                        File(defaultReportDir(), manifestFileName(timestamped)).absolutePath
                    }
                }
            return ExperimentManifestWriter(File(path))
        }

        private fun manifestFileName(timestamped: Boolean): String {
            if (!timestamped) return "manifest.jsonl"
            val ts = TS_FORMAT.format(Instant.now())
            return "manifest_$ts.jsonl"
        }

        private fun defaultReportDir(): File {
            val root = findProjectRoot()
            return File(root, "reports")
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
}
