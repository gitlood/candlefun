# Vacuum (Liquidity Vacuum Breakout)

Captures fast jumps when depth collapses, spread widens, and trade burst aligns.

## Backtest

```
./gradlew :vacuum:runBacktest
```

## Testnet (futures)

```
./gradlew :vacuum:runTestnet
```

## Live paper

```
./gradlew :vacuum:runLive
```

## Key env vars

- `MARKETSTATE_CSV`, `SYMBOLS`, `REPLAY_SPEEDUP`
- `MARKETDATA_SOURCE` (`SPOT` or `FUTURES`), `TOP_N`
- `BINANCE_TEST_KEY`, `BINANCE_TEST_SECRET`
- `BINANCE_TESTNET_API_KEY`, `BINANCE_TESTNET_SECRET_KEY`
- `BINANCE_FUTURES_TESTNET_API_KEY`, `BINANCE_FUTURES_TESTNET_SECRET_KEY`
- `FILLS_POLL_MS`, `LEVERAGE`
- `LOG_PNL_EVERY_MS`, `RESET_WALLET`, `WALLET_AUTOPERSIST`
- `TICK_MS`, `DEPTH_LEVELS`, `DEPTH_SPEED_MS`, `SNAPSHOT_DEPTH`
- `DEPTH_WINDOW_MS`, `DEPTH_DROP_PCT`, `DEPTH_REFILL_PCT`
- `SPREAD_WINDOW_MS`, `SPREAD_WIDEN_PCT`, `MAX_SPREAD_PCT`
- `MIN_TRADE_COUNT_1S`, `MIN_TRADE_IMB_1S`
- `ORDER_QTY`, `PRICE_TICK`, `QTY_STEP`
- `ENTRY_COOLDOWN_MS`, `ORDER_TTL_MS`, `MAX_HOLD_MS`
- `TRAILING_STOP_BPS`
- `SLIPPAGE_PAUSE_BPS`, `TAIL_LOSS_BPS`, `MAX_TAIL_LOSSES`, `PAUSE_MS`
- `LOG_SIGNALS`, `LOG_KPI_EVERY_MS`

## KPI Output (Reporting)

KPI summaries are printed to stdout at `LOG_KPI_EVERY_MS` via `VacuumReport`.

Key fields:
- `avgSlippageBps`, `avgAdverseMoveBps`
- `tailLossCount`, `lastSlippageBps`
- `cancelRate`, `staleCancelRate`
- `health_summary` line (standard cross-strategy format)

## Manifest Logging (JSONL)

- `MANIFEST_ENABLED` (default `true`)
- `MANIFEST_PATH` (file override)
- `MANIFEST_TIMESTAMPED` (default `false`)
- `REPORT_DIR` (directory override)
- `RUN_ID`, `RUN_NOTES`

## How To Interpret The Report (AI Notes)

- **avgSlippageBps** high means you are crossing into thin book.
- **avgAdverseMoveBps** negative means post-fill drift against you.
- **tailLossCount** rising implies tail events are punishing.

## Tuning Playbook

- **If slippage/adverse is high**:
  - Increase `DEPTH_DROP_PCT` and `SPREAD_WIDEN_PCT`.
  - Increase `ENTRY_COOLDOWN_MS` and `ORDER_TTL_MS`.
  - Reduce `ORDER_QTY`.

- **If no trades occur**:
  - Lower `DEPTH_DROP_PCT`, `SPREAD_WIDEN_PCT`, or `MIN_TRADE_COUNT_1S`.
  - Reduce `ENTRY_COOLDOWN_MS`.

- **If tail losses spike**:
  - Lower `TAIL_LOSS_BPS`, increase `SLIPPAGE_PAUSE_BPS`.
  - Increase `PAUSE_MS` after tail events.

## Decision Tree

```
Are tailLossCount and slippage low?
  ├─ Yes → Keep settings; extend duration.
  └─ No  → Increase thresholds, reduce size, lengthen cooldown.
```
