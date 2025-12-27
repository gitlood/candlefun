# OFI (Kukanov) Module

Implements Cont–Kukanov–Stoikov order‑flow imbalance (OFI) and a short‑horizon strategy.

## Runners

Backtest (replay from `marketstate.csv`):

```
./gradlew :ofi-kukanov:run
```

Live (paper) runner:

```
./gradlew :ofi-kukanov:runLive
```

Live (testnet execution, futures only):

```
./gradlew :ofi-kukanov:runTestnet
```

## Key env vars

Signal:
- `OFI_WINDOW_MS` (default 1000)
- `OFI_DEPTH_LEVELS` (default 1)
- Normalization is notional OFI / depth notional

Strategy:
- `OFI_ENTRY_THRESHOLD` (default 0.002)
- `OFI_EXIT_THRESHOLD` (default 0.0005)
- `OFI_TAKE_MULT` (default 2.5)
- `OFI_TAKE_MIN_EDGE_BPS` (default 2.0)
- `OFI_MAX_HOLD_MS` (default 1000)
- `OFI_MIN_SIGNAL_MS` (default 100)
- `ORDER_TTL_MS` (default 300)
- `TAKE_ORDER_TTL_MS` (default 150)
- `SPREAD_WINDOW_MS` (default 1000)
- `MAX_SPREAD_PCT` (default 0.002)
- `SPREAD_DEV_PCT` (default 0.2)
- `SPREAD_MIN_SAMPLES` (default 5)
- `MIN_DEPTH_NOTIONAL`, `MIN_DEPTH_QTY`
- `USE_TRADE_CONFIRM`, `MIN_TRADE_COUNT_1S`, `MIN_TRADE_IMB_1S`
- `JOIN_OFFSET_TICKS` (default 0)
- `ORDER_STYLE` (`JOIN` or `TAKE`)
- `LOG_KPI_EVERY_MS` (default 60000)

Execution:
- `ORDER_QTY`, `PRICE_TICK`, `QTY_STEP`
- `SIM_ORDER_LATENCY_MS`, `SIM_QUEUE_BUFFER`, `SIM_MAX_QUEUE_LEVELS`
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`

Market data:
- `MARKETSTATE_CSV`, `SYMBOLS`, `REPLAY_SPEEDUP`
- `MARKETDATA_SOURCE` (`SPOT` or `FUTURES`), `TOP_N`
- `TICK_MS`, `DEPTH_LEVELS`, `DEPTH_SPEED_MS`, `SNAPSHOT_DEPTH`
