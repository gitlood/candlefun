package com.example.stackeddca

import com.example.network.UniverseConfig

enum class Mode {
    DISCOVER, INGEST, BACKTEST, ALL, SWEEP,
    LIVE
}


enum class SizeMode {
    EQUAL,
    GEOMETRIC
}

enum class FillMode {
    OPTIMISTIC,
    CLOSE,
    TWO_CLOSE
}

enum class VolatilityMode {
    ATR_MEAN,
    RANGE
}

enum class EntryAnchor {
    HIGH_24,
    CLOSE,
    LOW_24
}

data class IngestConfig(
    val interval: String = "1m",
    val dbDir: String = "stackeddca_data",
    val exportDir: String = "stackeddca_data/exports"
)

data class SweepConfig(
    val presetNames: List<String> = DEFAULT_SWEEP_PRESETS
)

data class BacktestConfig(
    val startEquity: Double = 100_000.0,
    val baseOrderNotional: Double = 1_000.0,
    val sizeMode: SizeMode = SizeMode.EQUAL,
    val sizeR: Double = 1.1,
    val maxLayers: Int = 10,
    val maxEquityPct: Double = 0.6,
    val maxNotional: Double? = null,
    val kEntry: Double = 1.0,
    val minStepPct: Double = 0.005,
    val minEntryDistPct: Double = 0.0,
    val maxEntryDistPct: Double = 0.0,
    val kTp: Double = 0.7,
    val trailArmedMinPct: Double = 0.002,
    val trailArmedVolFactor: Double = 0.2,
    val kTrail: Double = 1.0,
    val kHard: Double = 2.0,
    val feePct: Double = 0.001,
    val slippagePct: Double = 0.0005,
    val fillMode: FillMode = FillMode.OPTIMISTIC,
    val maxDrawdownForNewLayerPct: Double? = null,
    val forceExitAtEnd: Boolean = true,
    val minVolPctForEntry: Double = 0.0,
    val maxVolPctForEntry: Double = 0.0,
    val volMode: VolatilityMode = VolatilityMode.RANGE,
    val entryAnchor: EntryAnchor = EntryAnchor.CLOSE,
    val trendDays: Int = 0,
    val trendMinPct: Double = 0.0
)

data class AppConfig(
    val mode: Mode,
    val symbols: List<String>?,
    val universe: UniverseConfig,
    val ingest: IngestConfig,
    val backtest: BacktestConfig,
    val sweep: SweepConfig
)

fun parseArgs(args: Array<String>): AppConfig {
    val tokens = args.flatMap { arg ->
        arg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    val argMap = tokens.mapNotNull { token ->
        if (!token.startsWith("--") || !token.contains("=")) return@mapNotNull null
        val parts = token.removePrefix("--").split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        parts[0] to parts[1]
    }.toMap()

    val mode = argMap["mode"]?.uppercase()?.let { Mode.valueOf(it) } ?: Mode.ALL
    val symbols = argMap["symbols"]?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }

    val defaultExcludes = setOf("USDCUSDT", "FDUSDUSDT")
    val excludeSymbols = if (argMap.containsKey("exclude-symbols")) {
        argMap["exclude-symbols"]?.split(",")?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    } else {
        defaultExcludes
    }

    val universe = UniverseConfig(
        quoteAssets = argMap["quote-assets"]?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?.toSet() ?: setOf("USDT"),
        minQuoteVolume = argMap["min-quote-volume"]?.toDoubleOrNull() ?: 0.0,
        minTrades = argMap["min-trades"]?.toIntOrNull() ?: 0,
        maxSymbols = argMap["top"]?.toIntOrNull() ?: 10,
        includeSymbols = argMap["include-symbols"]?.split(",")?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        excludeSymbols = excludeSymbols
    )

    val dbDir = argMap["db-dir"] ?: "stackeddca_data"
    val ingest = IngestConfig(
        interval = argMap["interval"] ?: "1m",
        dbDir = dbDir,
        exportDir = argMap["export-dir"] ?: "$dbDir/exports"
    )

    val backtest = BacktestConfig(
        startEquity = argMap["start-equity"]?.toDoubleOrNull() ?: 100_000.0,
        baseOrderNotional = argMap["base-notional"]?.toDoubleOrNull() ?: 1_000.0,
        sizeMode = argMap["size-mode"]?.uppercase()?.let { SizeMode.valueOf(it) } ?: SizeMode.EQUAL,
        sizeR = argMap["size-r"]?.toDoubleOrNull() ?: 1.1,
        maxLayers = argMap["max-layers"]?.toIntOrNull() ?: 10,
        maxEquityPct = argMap["max-equity-pct"]?.toDoubleOrNull() ?: 0.6,
        maxNotional = argMap["max-notional"]?.toDoubleOrNull(),
        kEntry = argMap["k-entry"]?.toDoubleOrNull() ?: 1.0,
        minStepPct = argMap["min-step-pct"]?.toDoubleOrNull() ?: 0.005,
        minEntryDistPct = argMap["min-entry-dist-pct"]?.toDoubleOrNull() ?: 0.0,
        maxEntryDistPct = argMap["max-entry-dist-pct"]?.toDoubleOrNull() ?: 0.0,
        kTp = argMap["k-tp"]?.toDoubleOrNull() ?: 0.7,
        trailArmedMinPct = argMap["trail-armed-min-pct"]?.toDoubleOrNull() ?: 0.002,
        trailArmedVolFactor = argMap["trail-armed-vol-factor"]?.toDoubleOrNull() ?: 0.2,
        kTrail = argMap["k-trail"]?.toDoubleOrNull() ?: 1.0,
        kHard = argMap["k-hard"]?.toDoubleOrNull() ?: 2.0,
        feePct = argMap["fee-pct"]?.toDoubleOrNull() ?: 0.001,
        slippagePct = argMap["slippage-pct"]?.toDoubleOrNull() ?: 0.0005,
        fillMode = argMap["fill-mode"]?.uppercase()?.let { FillMode.valueOf(it) } ?: FillMode.OPTIMISTIC,
        maxDrawdownForNewLayerPct = argMap["max-dd-new-layer-pct"]?.toDoubleOrNull(),
        forceExitAtEnd = argMap["force-exit-at-end"]?.toBooleanStrictOrNull() ?: true,
        minVolPctForEntry = argMap["min-vol-pct"]?.toDoubleOrNull() ?: 0.0,
        maxVolPctForEntry = argMap["max-vol-pct"]?.toDoubleOrNull() ?: 0.0,
        volMode = argMap["vol-mode"]?.uppercase()?.let { VolatilityMode.valueOf(it) } ?: VolatilityMode.RANGE,
        entryAnchor = argMap["entry-anchor"]?.uppercase()?.let { EntryAnchor.valueOf(it) } ?: EntryAnchor.CLOSE,
        trendDays = argMap["trend-days"]?.toIntOrNull() ?: 0,
        trendMinPct = argMap["trend-min-pct"]?.toDoubleOrNull() ?: 0.0
    )

    val sweep = SweepConfig(
        presetNames = argMap["presets"]?.split(",")?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() } ?: DEFAULT_SWEEP_PRESETS
    )

    return AppConfig(
        mode = mode,
        symbols = symbols,
        universe = universe,
        ingest = ingest,
        backtest = backtest,
        sweep = sweep
    )
}

typealias BacktestConfigFactory = (BacktestConfig) -> BacktestConfig

data class SweepPresetResult(
    val name: String,
    val config: BacktestConfig
)

private val DEFAULT_SWEEP_PRESETS = listOf("conservative", "balanced", "aggressive", "realistic")

private val SWEEP_PRESETS: Map<String, BacktestConfigFactory> = mapOf(
    "conservative" to { base ->
        base.copy(
            kEntry = 0.95,
            minStepPct = 0.006,
            minEntryDistPct = 0.004,
            maxEntryDistPct = 0.04,
            kTp = 0.55,
            kTrail = 0.8,
            kHard = 2.2,
            maxLayers = 6,
            maxNotional = 18_000.0,
            fillMode = FillMode.CLOSE,
            slippagePct = 0.001,
            feePct = 0.0015,
            minVolPctForEntry = 0.012,
            maxVolPctForEntry = 0.3,
            volMode = VolatilityMode.RANGE,
            entryAnchor = EntryAnchor.CLOSE,
            trendDays = 3,
            trendMinPct = 0.0,
            maxDrawdownForNewLayerPct = 0.08
        )
    },
    "balanced" to { base ->
        base.copy(
            kEntry = 0.85,
            minStepPct = 0.005,
            minEntryDistPct = 0.003,
            maxEntryDistPct = 0.03,
            kTp = 0.65,
            kTrail = 0.9,
            kHard = 2.0,
            maxLayers = 7,
            maxNotional = 20_000.0,
            sizeMode = SizeMode.GEOMETRIC,
            sizeR = 1.1,
            fillMode = FillMode.CLOSE,
            slippagePct = 0.0008,
            minVolPctForEntry = 0.01,
            maxVolPctForEntry = 0.3,
            volMode = VolatilityMode.RANGE,
            entryAnchor = EntryAnchor.CLOSE,
            trendDays = 2
        )
    },
    "aggressive" to { base ->
        base.copy(
            kEntry = 0.7,
            minStepPct = 0.004,
            minEntryDistPct = 0.002,
            maxEntryDistPct = 0.025,
            kTp = 1.2,
            kTrail = 1.0,
            kHard = 1.5,
            maxLayers = 8,
            maxNotional = 25_000.0,
            sizeMode = SizeMode.GEOMETRIC,
            sizeR = 1.2,
            fillMode = FillMode.OPTIMISTIC,
            slippagePct = 0.0005,
            minVolPctForEntry = 0.008,
            maxVolPctForEntry = 0.35,
            volMode = VolatilityMode.RANGE,
            entryAnchor = EntryAnchor.CLOSE,
            trendDays = 0
        )
    },
    "realistic" to { base ->
        base.copy(
            kEntry = 0.95,
            minStepPct = 0.006,
            minEntryDistPct = 0.004,
            maxEntryDistPct = 0.035,
            kTp = 0.6,
            kTrail = 0.85,
            kHard = 2.0,
            maxLayers = 6,
            maxNotional = 22_000.0,
            fillMode = FillMode.CLOSE,
            feePct = 0.0012,
            slippagePct = 0.0010,
            maxDrawdownForNewLayerPct = 0.05,
            minVolPctForEntry = 0.011,
            maxVolPctForEntry = 0.3,
            volMode = VolatilityMode.RANGE,
            entryAnchor = EntryAnchor.CLOSE,
            trendDays = 3
        )
    }
)

fun buildPresets(base: BacktestConfig, names: List<String>): List<SweepPresetResult> {
    return names.mapNotNull { request ->
        val key = request.lowercase()
        SWEEP_PRESETS[key]?.invoke(base)?.let { config ->
            SweepPresetResult(request, config)
        }
    }
}
