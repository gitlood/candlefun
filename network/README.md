# Network Module

This module encapsulates every Binance integration (Spot, Futures, REST, WebSocket) plus the candle collector and supporting utilities.

## Key Services

- `BinanceApiServiceImpl` / `BinancePrivateApi`: Spot REST API helpers for tickers, depth, trades, order routes.
- `BinanceTestNetApiServiceImpl`: Same stack pinning to Binance TestNet.
- `BinanceUniverse`: Fetches symbol metadata, volumes, and the `resolveSymbols` helper used by `superbot`.
- `LiveKlineRepoImpl`, `LiveDepthRepoImpl`, `LiveAggTradeRepoImpl`, and `LiveBookTickerRepoImpl`: Wrap real-time feeds into `Flow`s consumed by `MarketState`.
- `BinanceWebSocketServiceImpl` & `BinanceOrderBookServiceImpl`: Manage subscriptions, reconnects, and top-of-book snapshots.
- `BinanceSigner`: HMAC SHA256 signing utility used by all private endpoints.

## Candle Collector

- `UniversalCandleCollector` (`./gradlew :network:run`) backfills candles into `CandlesStore` (`candles.db` default path). Override via `CANDLE_DB_PATH`.
- `CandleCollectorConfig` drives symbol lists, intervals, backfill window, and JDBC connection.
- `CandleStore` implements `CandleStorePort` for persistence (insert/delete) and powers offline replay + analytics.

## Build & Test

- Run the module tests: `./gradlew :network:test`.
