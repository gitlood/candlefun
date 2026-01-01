package com.example.network

import com.example.network.dto.WsKlineData
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.LiveKlineRepo
import com.example.platform.model.LiveCandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class LiveKlineRepoImpl(
    private val webSocketService: BinanceWebSocketService
) : LiveKlineRepo {

    override fun streamClosed1mCandles(
        symbols: List<String>
    ): Flow<Pair<String, LiveCandle>> {
        val streams = symbols.map { "${it.lowercase()}@kline_1m" }

        return webSocketService.connect(streams)
            .mapNotNull { env ->
                val data = env.data as? WsKlineData ?: return@mapNotNull null
                
                if (!data.kline.isClosed) return@mapNotNull null

                try {
                    val symbol = data.symbol.uppercase()
                    val k = data.kline
                    val candle = LiveCandle(
                        openTime = k.openTime,
                        open = k.open.toDoubleOrNull() ?: return@mapNotNull null,
                        high = k.high.toDoubleOrNull() ?: return@mapNotNull null,
                        low = k.low.toDoubleOrNull() ?: return@mapNotNull null,
                        close = k.close.toDoubleOrNull() ?: return@mapNotNull null
                    )
                    symbol to candle
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
            }
    }
}
