# Market Data Impl Module

This module implements the `marketdata-domain` contracts with concrete repositories, recorders, and replay facilities backed by Binance + CSV/SQLite storage.

## Implementations

- `MarketdataImplModule`: DI wiring for spot/futures `MarketStateRepository`s, `FuturesMarketStateRepository`, and recording/replay components.
- `MarketdataReplayModule` & `MarketStateReplayer`: Replay `marketstate.csv` dumps for backtests (includes fast CSV iterator with filtering).
- `MarketStateRecorder` / `MarketStateRecorderRunner`: Capture live `MarketState` snapshots and persist them for downstream replays.
- `SqliteCandleHistoryRepository`: Stores candle history in a lightweight SQLite file (`candelstore.db`) for analytics.
- `MarketdataRoutingModule` + `MarketdataRoutingConfig`: Choose between replay vs. live feeds and configure snapshot cadence.

## Build & Test

- Run `./gradlew :marketdata-impl:test` to validate replay, recorder, and DI helpers.
