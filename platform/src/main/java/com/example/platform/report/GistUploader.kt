package com.example.platform.report

import java.io.File
import java.util.Properties
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

object GistUploader {
    data class Config(
        val token: String,
        val gistId: String,
        val maxBytes: Long,
        val enabled: Boolean
    )

    fun fromEnv(): Config? {
        val enabled = readBool("GIST_ENABLED") ?: false
        if (!enabled) return null
        val token = readString("GIST_TOKEN")
            ?: readString("GITHUB_TOKEN")
            ?: return null
        val gistId = readString("GIST_ID") ?: return null
        val maxBytes = readString("GIST_MAX_BYTES")?.toLongOrNull() ?: 2_000_000L
        return Config(token = token, gistId = gistId, maxBytes = maxBytes, enabled = true)
    }

    fun installUploadOnShutdown(label: String, files: List<File>) {
        val config = fromEnv() ?: return
        val cleanLabel = label.trim().replace(' ', '_')
        Runtime.getRuntime().addShutdownHook(
            Thread {
                try {
                    upload(config, cleanLabel, files)
                } catch (e: Exception) {
                    println("gist_upload_failed: ${e.message}")
                }
            }
        )
    }

    fun upload(config: Config, label: String, files: List<File>): Boolean {
        val filePayload = buildFilePayload(label, files, config.maxBytes)
        if (filePayload.isEmpty()) {
            println("gist_upload_skip: no files")
            return false
        }
        val description = "CandleFun report ${label} @ ${Instant.now()}"
        val body = buildJsonPayload(description, filePayload)
        val url = URL("https://api.github.com/gists/${config.gistId}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Authorization", "Bearer ${config.token}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "CandleFun")
        conn.doOutput = true
        conn.outputStream.use { out ->
            out.write(body.toByteArray(StandardCharsets.UTF_8))
        }
        val code = conn.responseCode
        if (code in 200..299) {
            println("gist_upload_ok: files=${filePayload.keys.size}")
            return true
        }
        val error = runCatching { conn.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8) }
            .getOrNull()
        println("gist_upload_error: http=$code body=${error ?: "n/a"}")
        return false
    }

    private fun buildFilePayload(
        label: String,
        files: List<File>,
        maxBytes: Long
    ): Map<String, String> {
        val payload = LinkedHashMap<String, String>()
        files.forEach { file ->
            if (!file.exists()) return@forEach
            val size = file.length()
            if (size <= 0 || size > maxBytes) {
                println("gist_upload_skip: ${file.name} sizeBytes=$size")
                return@forEach
            }
            val name = "${label}_${file.name}"
            payload[name] = file.readText()
        }
        return payload
    }

    private fun buildJsonPayload(description: String, files: Map<String, String>): String {
        val fileEntries = files.entries.joinToString(",") { entry ->
            val filename = encodeJsonString(entry.key)
            val content = encodeJsonString(entry.value)
            "\"$filename\":{\"content\":\"$content\"}"
        }
        val desc = encodeJsonString(description)
        return "{\"description\":\"$desc\",\"files\":{$fileEntries}}"
    }

    private fun encodeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
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
        return sb.toString()
    }

    private fun readString(key: String): String? {
        System.getenv(key)?.let { return it }
        val props = loadLocalProperties() ?: return null
        return props.getProperty(key)
    }

    private fun readBool(key: String): Boolean? {
        val raw = readString(key) ?: return null
        return raw.toBooleanStrictOrNull()
    }

    private fun loadLocalProperties(): Properties? {
        val root = findProjectRoot()
        val file = File(root, "local.properties")
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
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
