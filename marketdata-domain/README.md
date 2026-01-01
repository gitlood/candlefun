# Market Data Domain Module

This module defines the interfaces and supporting models used across the market data stack (superbot, strategy backtests, runners). It does not depend on Android.

## Key Interfaces & Models

- `MarketStateRepository`: Streams order book snapshots, trade tape, volatility/imbalance metrics, and other microstructure stats.
- `FuturesMarketStateRepository`: Specialization that restricts to futures symbols and exposes `streamMarketState`.
- `CandleHistoryRepository` / `TradeHistoryRepository`: Historical accessors for candlesticks and trades.
- `MarketStateConfig`: Configures ticks, depth levels/speed, and snapshot depth bias (used by `superbot` and runners).
- `Symbol`, `MarketState`, and the `GetCandlesForDaysUseCase` bring uniform types to downstream consumers.

## Build & Test

- Run `./gradlew :marketdata-domain:test` to cover repository interfaces and model serialization helpers.
