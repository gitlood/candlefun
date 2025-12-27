# Survivor (Funding + Basis Carry)

Perps carry strategy focused on funding capture and basis normalization. Uses a CSV feed for backtest and live tailing.

## CSV format

Header:

```
symbol,timestampMs,fundingRate,nextFundingTimeMs,markPrice,indexPrice,spreadPct,volatility,openInterest
```

## Testnet (futures)

```
./gradlew :survivor:runTestnet
```

## Backtest

```
./gradlew :survivor:runBacktest
```

## Live tail (paper)

```
./gradlew :survivor:runTail
```

## Key env vars

- `BINANCE_TEST_KEY`, `BINANCE_TEST_SECRET`
- `BINANCE_TESTNET_API_KEY`, `BINANCE_TESTNET_SECRET_KEY`
- `BINANCE_FUTURES_TESTNET_API_KEY`, `BINANCE_FUTURES_TESTNET_SECRET_KEY`
- `SYMBOL` (default `BTCUSDT`)
- `SURVIVOR_CSV` (backtest input)
- `SURVIVOR_TAIL_CSV` (live tail input)
- `REPLAY_SPEEDUP`
- `LOG_KPI_EVERY_MS`
- `TAIL_POLL_MS`
- `FUNDING_POLL_MS`, `POSITION_POLL_MS`
- `RECORD_SURVIVOR_CSV`, `SURVIVOR_RECORD_PATH`, `RECORD_EVERY_MS`
- `RECORD_TIMESTAMPED`, `RECORD_TRUNCATE`
- `ENTRY_FUNDING`, `EXIT_FUNDING`
- `BASIS_STOP_PCT`
- `MAX_VOL`, `MAX_SPREAD_PCT`
- `MAX_OI_JUMP_PCT`, `OI_WINDOW_MS`
- `MAX_HOLD_MS`, `ORDER_TTL_MS`
- `ORDER_QTY`
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`
- `BORROW_FEE_PCT_DAY`
- `LOG_SIGNALS`

## KPI Output (Reporting)

KPI summaries are printed to stdout at `LOG_KPI_EVERY_MS` via `SurvivorReport`.

Key fields:
- `netCarry`, `realizedFunding`, `realizedFees`, `borrowCosts`
- `expectedCarry`, `worstBasisAbsPct`
- `cancelRate`, `staleCancelRate`
- `health_summary` line (standard cross-strategy format)

## Manifest Logging (JSONL)

- `MANIFEST_ENABLED` (default `true`)
- `MANIFEST_PATH` (file override)
- `MANIFEST_TIMESTAMPED` (default `false`)
- `REPORT_DIR` (directory override)
- `RUN_ID`, `RUN_NOTES`

## How To Interpret The Report (AI Notes)

- **netCarry** should be positive over time; negative means fees/borrow or bad basis.
- **worstBasisAbsPct** high indicates basis risk spikes.
- **expectedCarry** vs **netCarry** shows slippage/fee drag.
- **cancelRate** high indicates unstable conditions or overly tight gates.

## Tuning Playbook

- **If `netCarry` < 0**:
  - Increase `ENTRY_FUNDING` magnitude or tighten `EXIT_FUNDING`.
  - Lower `ORDER_QTY` to reduce borrow/fee drag.
  - Tighten `MAX_SPREAD_PCT` and `MAX_VOL`.

- **If `worstBasisAbsPct` is high**:
  - Reduce `MAX_OI_JUMP_PCT` or shorten `OI_WINDOW_MS`.
  - Lower `MAX_HOLD_MS`.

- **If no trades occur**:
  - Loosen `ENTRY_FUNDING`, widen `BASIS_STOP_PCT`.
  - Check CSV freshness or funding polling.

## Decision Tree

```
Is netCarry positive?
  ├─ Yes → Is worstBasisAbsPct stable?
  │        ├─ Yes → Keep settings; extend duration.
  │        └─ No  → Tighten basis risk limits.
  └─ No  → Are fees/borrow high?
           ├─ Yes → Reduce size, tighten entry/exit.
           └─ No  → Loosen entry threshold; check data freshness.
```
