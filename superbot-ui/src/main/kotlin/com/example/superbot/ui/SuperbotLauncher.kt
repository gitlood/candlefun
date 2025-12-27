package com.example.superbot.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JComponent
import javax.swing.text.DefaultCaret
import kotlin.concurrent.thread

private val STRATEGY_TASKS = listOf(
    StrategyTask("Avellaneda MM Backtest", ":avellaneda-mm:run"),
    StrategyTask("Avellaneda MM Testnet", ":avellaneda-live:runTestnet"),
    StrategyTask("OFI Kukanov Backtest", ":ofi-kukanov:run"),
    StrategyTask("OFI Kukanov Testnet", ":ofi-kukanov:runTestnet"),
    StrategyTask("Vacuum Backtest", ":vacuum:runBacktest"),
    StrategyTask("Vacuum Testnet", ":vacuum:runTestnet"),
    StrategyTask("Pairs Backtest", ":pairs:runBacktest"),
    StrategyTask("Pairs Testnet", ":pairs:runTestnet"),
    StrategyTask("Survivor Backtest", ":survivor:runBacktest"),
    StrategyTask("Survivor Testnet", ":survivor:runTestnet"),
    StrategyTask("Survivor Tail", ":survivor:runTail")
)

data class StrategyTask(val label: String, val gradleTask: String)

fun main() {
    SwingUtilities.invokeLater { SuperbotLauncher().show() }
}

class SuperbotLauncher {
    private val logTabs = JTabbedPane()
    private val textAreas = mutableMapOf<String, JTextArea>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val taskPanels = mutableListOf<TaskControlPanel>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            scheduler.shutdownNow()
        })
    }

    fun show() {
        val root = findProjectRoot()
        val frame = JFrame("Superbot Launcher")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.layout = BorderLayout(8, 8)

        val configPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8))
        val minimalTelemetryCheckbox = JCheckBox("Minimal Telemetry")
            .apply { isSelected = true }
        val autoUploadCheckbox = JCheckBox("Auto Upload to Gist")
            .apply { isSelected = true }
        val symbolField = JTextField("BTCUSDT", 10).apply {
            toolTipText = "Comma-separated symbols applied to every strategy run"
        }
        val symbolLabel = JLabel("Symbols:")
        val reportIntervalField = JTextField("60", 6).apply {
            toolTipText = "Report interval in seconds (overrides defaults)"
        }
        val reportIntervalLabel = JLabel("Report s:")
        val durationFieldAll = JTextField("300", 5)
        val durationLabel = JLabel("Duration (s):")
        val applyDurationButton = JButton("Apply to All").apply {
            addActionListener {
                val seconds = durationFieldAll.text.toLongOrNull()?.coerceAtLeast(1L) ?: 300L
                taskPanels.forEach { it.updateDuration(seconds) }
            }
        }
        val resetButton = JButton("Reset Reports").apply {
            addActionListener {
                resetReports(root)
            }
        }
        val resetLogsButton = JButton("Reset Logs").apply {
            addActionListener {
                resetLogs()
            }
        }
        val startAllButton = JButton("Start All").apply {
            addActionListener {
                taskPanels.forEach { it.startFromLauncher() }
            }
        }
        val stopAllButton = JButton("Stop All").apply {
            addActionListener {
                taskPanels.forEach { it.stopFromLauncher("stop all") }
            }
        }
        configPanel.add(minimalTelemetryCheckbox)
        configPanel.add(autoUploadCheckbox)
        configPanel.add(symbolLabel)
        configPanel.add(symbolField)
        configPanel.add(reportIntervalLabel)
        configPanel.add(reportIntervalField)
        configPanel.add(durationLabel)
        configPanel.add(durationFieldAll)
        configPanel.add(applyDurationButton)
        configPanel.add(resetButton)
        configPanel.add(resetLogsButton)
        configPanel.add(startAllButton)
        configPanel.add(stopAllButton)

        val tasksPanel = JPanel(GridBagLayout())
        val columnWeights = listOf(0.5, 0.1, 0.1, 0.15, 0.15, 0.15, 0.05)
        fun addHeaderRow() {
            val titles = listOf("Strategy", "Duration (s)", "Actions", "Time left", "Status", "Report", "Link")
            titles.forEachIndexed { index, title ->
                val c = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    insets = Insets(4, 4, 4, 4)
                    fill = GridBagConstraints.HORIZONTAL
                    gridx = index
                    gridy = 0
                    weightx = columnWeights[index]
                }
                tasksPanel.add(JLabel(title), c)
            }
        }
        addHeaderRow()
        STRATEGY_TASKS.forEachIndexed { index, task ->
            val panel = TaskControlPanel(
                task = task,
                root = root,
                scheduler = scheduler,
                logConsumer = this@SuperbotLauncher::appendLog,
                minimalTelemetry = { minimalTelemetryCheckbox.isSelected },
                autoUpload = { autoUploadCheckbox.isSelected },
                symbolsProvider = { symbolField.text.trim() },
                reportIntervalProvider = { reportIntervalField.text.trim() }
            )
            taskPanels.add(panel)
            panel.addToGrid(tasksPanel, row = index + 1, weights = columnWeights)
        }

        val tasksScroll = JScrollPane(tasksPanel)
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, tasksScroll, logTabs).apply {
            resizeWeight = 0.7
            preferredSize = Dimension(0, 400)
            dividerSize = 6
            isOneTouchExpandable = true
            minimumSize = Dimension(0, 200)
        }

        logTabs.preferredSize = Dimension(0, 180)
        frame.add(configPanel, BorderLayout.NORTH)
        frame.add(splitPane, BorderLayout.CENTER)

        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun appendLog(label: String, line: String) {
        SwingUtilities.invokeLater {
            val area = textAreas.getOrPut(label) {
                val newArea = JTextArea().apply {
                    isEditable = false
                    lineWrap = false
                    val caret = caret as DefaultCaret
                    caret.updatePolicy = DefaultCaret.ALWAYS_UPDATE
                }
                logTabs.addTab(label, JScrollPane(newArea))
                newArea
            }
            area.append(line.trimEnd() + "\n")
        }
    }

    private fun resetReports(root: File) {
        val telemetryDir = File(root, "reports/telemetry")
        deleteDirectoryContents(telemetryDir)
        val manifestFile = File(root, "reports/manifest.jsonl")
        if (manifestFile.exists()) {
            manifestFile.delete()
            appendLog("system", "reset: deleted ${manifestFile.path}")
        }
    }

    private fun resetLogs() {
        SwingUtilities.invokeLater {
            textAreas.clear()
            logTabs.removeAll()
        }
    }

    private fun deleteDirectoryContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        appendLog("system", "reset: cleared ${dir.path}")
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return dir
        }
    }
}

class TaskControlPanel(
    private val task: StrategyTask,
    private val root: File,
    private val scheduler: ScheduledExecutorService,
    private val logConsumer: (String, String) -> Unit,
    private val minimalTelemetry: () -> Boolean,
    private val autoUpload: () -> Boolean,
    private val symbolsProvider: () -> String,
    private val reportIntervalProvider: () -> String
) : JPanel() {
    private val statusLabel = JLabel("Stopped").apply {
        horizontalAlignment = SwingConstants.LEFT
        foreground = Color.DARK_GRAY
    }
    private val durationField = JTextField("300", 5).apply {
        preferredSize = Dimension(60, 24)
        toolTipText = "Duration (seconds) before the task auto-stops"
    }
    private val countdownLabel = JLabel("Time left: --").apply {
        horizontalAlignment = SwingConstants.LEFT
    }
    private val reportLabel = JLabel("Report: pending").apply {
        horizontalAlignment = SwingConstants.LEFT
        foreground = Color.DARK_GRAY
    }
    private val openReportButton = JButton("Open Report").apply {
        isEnabled = false
        addActionListener { openReportLink() }
    }
    private var lastReportPath: String? = null
    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private var process: Process? = null
    private var stopFuture: ScheduledFuture<*>? = null
    private var countdownFuture: ScheduledFuture<*>? = null
    private var countdownStartMs = 0L
    private var targetDurationSeconds = 300L

    private val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(startButton)
        add(stopButton)
    }

    init {
        startButton.addActionListener { startProcess() }
        stopButton.addActionListener { stopProcess("stopped by user") }
    }

    fun addToGrid(parent: JPanel, row: Int, weights: List<Double>) {
        val base = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 4, 2, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        val components = listOf<JComponent>(
            JLabel(task.label),
            durationField,
            actionsPanel,
            countdownLabel,
            statusLabel,
            reportLabel,
            openReportButton
        )
        components.forEachIndexed { index, component ->
            val c = base.clone() as GridBagConstraints
            c.gridx = index
            c.gridy = row
            c.weightx = weights[index]
            parent.add(component, c)
        }
    }

    fun updateDuration(seconds: Long) {
        SwingUtilities.invokeLater {
            durationField.text = seconds.toString()
            targetDurationSeconds = seconds
        }
        setReportStatus("Report: pending", Color.DARK_GRAY)
    }

    fun startFromLauncher() {
        if (SwingUtilities.isEventDispatchThread()) {
            startProcess()
        } else {
            SwingUtilities.invokeLater { startProcess() }
        }
    }

    fun stopFromLauncher(reason: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            stopProcess(reason)
        } else {
            SwingUtilities.invokeLater { stopProcess(reason) }
        }
    }

    private fun startProcess() {
        if (process != null) return
        val durationSeconds = durationField.text.toLongOrNull()?.coerceAtLeast(1L) ?: 300L
        val env = buildEnv()
        try {
            val builder = ProcessBuilder("./gradlew", task.gradleTask).apply {
                directory(root)
                redirectErrorStream(true)
            }
            builder.environment().putAll(env)
            val proc = builder.start()
            process = proc
            startButton.isEnabled = false
            stopButton.isEnabled = true
            setStatus("Running (pid=${proc.pid()})", Color.BLUE)
            setReportStatus("Report: pending", Color.DARK_GRAY)
            logConsumer(task.label, "started duration=${durationSeconds}s")
            targetDurationSeconds = durationSeconds
            countdownStartMs = System.currentTimeMillis()
            scheduleCountdown(durationSeconds)
            stopFuture = scheduler.schedule({
                SwingUtilities.invokeLater { stopProcess("timeout") }
            }, durationSeconds, TimeUnit.SECONDS)
            thread { drainStream(proc, task.label) }
            thread {
                val exitCode = proc.waitFor()
                logConsumer(task.label, "exited code=$exitCode")
                val color = if (exitCode == 0) Color(0, 128, 0) else Color.RED
                SwingUtilities.invokeLater {
                    setStatus("Stopped (exit $exitCode)", color)
                    reportLabel.foreground = if (reportLabel.text.contains("uploaded")) Color(0, 128, 0) else reportLabel.foreground
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    process = null
                }
            }
        } catch (ex: IOException) {
            logConsumer(task.label, "failed to start: ${ex.message}")
            setStatus("Failed to start", Color.RED)
            cancelCountdown("stop")
        }
    }

    private fun stopProcess(reason: String) {
        stopFuture?.cancel(false)
        stopFuture = null
        cancelCountdown(reason)
        val running = process ?: return
        logConsumer(task.label, "stopping ($reason)")
        running.destroy()
        process = null
        SwingUtilities.invokeLater {
            startButton.isEnabled = true
            stopButton.isEnabled = false
            setStatus("Stopped ($reason)", Color.DARK_GRAY)
        }
    }

    private fun setStatus(text: String, color: Color) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            statusLabel.foreground = color
        }
    }

    private fun buildEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["TELEMETRY_ENABLED"] = "true"
        env["TELEMETRY_DIR"] = "reports/telemetry"
        env["FAST_MODE"] = "true"
        env["GIST_ENABLED"] = if (autoUpload()) "true" else "false"
        env["GIST_MAX_BYTES"] = "2000000"
        if (minimalTelemetry()) {
            env["LOG_KPI_EVERY_MS"] = "60000"
            env["LOG_EVERY_TICKS"] = "10000"
        } else {
            env["LOG_KPI_EVERY_MS"] = "10000"
            env["LOG_EVERY_TICKS"] = "500"
        }
        val reportSeconds = reportIntervalProvider().toLongOrNull()
        if (reportSeconds != null && reportSeconds > 0) {
            env["REPORT_EVERY_MS"] = (reportSeconds * 1000).toString()
        } else {
            env["REPORT_EVERY_MS"] = if (minimalTelemetry()) "300000" else "60000"
        }
        symbolsProvider().takeIf { it.isNotBlank() }?.let { env["SYMBOLS"] = it }
        return env
    }

    private fun drainStream(process: Process, prefix: String) {
        process.inputStream.bufferedReader().forEachLine { line ->
            logConsumer(task.label, line)
            if (line.contains("ReportPath")) {
                extractReportPath(line)
            }
            handleReportLine(line)
        }
    }

    private fun handleReportLine(line: String) {
        when {
            line.contains("gist_upload_ok") -> setReportStatus("Report: uploaded", Color(0, 180, 0))
            line.contains("gist_upload_error") -> setReportStatus("Report: upload error", Color.RED)
            line.contains("gist_upload_skip") -> setReportStatus("Report: skipped", Color.ORANGE)
        }
    }

    private fun markReportGenerated(text: String, color: Color) {
        if (reportLabel.text.contains("uploaded", ignoreCase = true)) return
        setReportStatus(text, color)
    }

    private fun extractReportPath(line: String) {
        val colon = line.indexOf(':')
        if (colon == -1) return
        val path = line.substring(colon + 1).trim()
        if (path.isBlank()) return
        lastReportPath = path
        SwingUtilities.invokeLater {
            openReportButton.isEnabled = true
            openReportButton.toolTipText = path
        }
        markReportGenerated("Report: ready", Color(0, 128, 255))
    }

    private fun openReportLink() {
        val path = lastReportPath ?: return
        val file = File(path)
        if (!file.exists()) {
            setReportStatus("Report missing", Color.RED)
            openReportButton.isEnabled = false
            return
        }
        try {
            Desktop.getDesktop().open(file)
        } catch (e: IOException) {
            setReportStatus("Report open failed", Color.RED)
        }
    }

    private fun scheduleCountdown(durationSeconds: Long) {
        countdownFuture?.cancel(false)
        countdownStartMs = System.currentTimeMillis()
        countdownFuture = scheduler.scheduleAtFixedRate({
            val elapsed = (System.currentTimeMillis() - countdownStartMs) / 1000
            val remaining = durationSeconds - elapsed
            if (remaining <= 0) {
                SwingUtilities.invokeLater {
                    countdownLabel.text = "Time limit reached"
                }
                countdownFuture?.cancel(false)
                countdownFuture = null
                return@scheduleAtFixedRate
            }
            val formatted = formatDuration(remaining)
            SwingUtilities.invokeLater {
                countdownLabel.text = "Time left: $formatted"
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun cancelCountdown(reason: String) {
        countdownFuture?.cancel(false)
        countdownFuture = null
        SwingUtilities.invokeLater {
            countdownLabel.text = when (reason) {
                "timeout" -> "Time limit reached"
                else -> "Time left: ${formatDuration(targetDurationSeconds)}"
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun setReportStatus(text: String, color: Color) {
        SwingUtilities.invokeLater {
            reportLabel.text = text
            reportLabel.foreground = color
        }
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(800, 40)
    }
}
