package com.example.marketdata.impl.di

import com.example.marketdata.impl.recording.MarketStateRecorder
import com.example.marketdata.impl.recording.MarketStateRecorderConfig
import com.example.marketdata.impl.replay.MarketStateReplayer
import org.koin.dsl.module
import java.io.File

val marketdataReplayModule = module {
    single { MarketStateRecorderConfig.default() }
    single { File(get<MarketStateRecorderConfig>().outputPath) }
    factory { MarketStateRecorder(get()) }
    factory { MarketStateReplayer(get()) }
}
