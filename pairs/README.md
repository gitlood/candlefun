# Pairs (Futures Stat-Arb)

Pairs mean reversion using rolling beta, spread z-score, and regime filters.

## Backtest

```
./gradlew :pairs:runBacktest
```

## Live paper

```
./gradlew :pairs:runLive
```

## Testnet (futures)

```
./gradlew :pairs:runTestnet
```

## Key env vars

- `MARKETSTATE_CSV`, `REPLAY_SPEEDUP`
- `SYMBOL_A`, `SYMBOL_B`
- `WINDOW_MS`, `MIN_SAMPLES`
- `ENTRY_Z`, `EXIT_Z`, `MAX_HOLD_MS`
- `MIN_CORR`, `MAX_VOL`, `TREND_COUNT_LIMIT`
- `NOTIONAL`, `PRICE_TICK`, `QTY_STEP`
- `ORDER_TTL_MS`
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`
- `TAIL_Z`, `LOG_KPI_EVERY_MS`, `LOG_SIGNALS`
- `BINANCE_TEST_KEY`, `BINANCE_TEST_SECRET`
- `BINANCE_TESTNET_API_KEY`, `BINANCE_TESTNET_SECRET_KEY`
- `BINANCE_FUTURES_TESTNET_API_KEY`, `BINANCE_FUTURES_TESTNET_SECRET_KEY`
- `FILLS_POLL_MS`, `LEVERAGE`
- `LOG_PNL_EVERY_MS`, `RESET_WALLET`, `WALLET_AUTOPERSIST`

## KPI Output (Reporting)

KPI summaries are printed to stdout at `LOG_KPI_EVERY_MS` via `PairsReport`.
If you upload KPI output to a GitHub Gist for sharing, include the Gist URL in your notes.
Gist upload (optional):

Key fields:
- `avgHalfLifeMs`, `tailEvents`
- `realizedPnL`, `totalFees`, `netPnL`
- `health_summary` line (standard cross-strategy format)

## Manifest Logging (JSONL)

- `MANIFEST_ENABLED` (default `true`)
- `MANIFEST_PATH` (file override)
- `MANIFEST_TIMESTAMPED` (default `false`)
- `REPORT_DIR` (directory override)
- `RUN_ID`, `RUN_NOTES`

## How To Interpret The Report (AI Notes)

- **netPnL** should trend positive over multiple cycles.
- **avgHalfLifeMs** too long means exits are slow or entry too aggressive.
- **tailEvents** high suggests regime mismatch or weak filters.

## Tuning Playbook

- **If `netPnL` < 0**:
  - Increase `ENTRY_Z`, or tighten `MIN_CORR`/`MAX_VOL`.
  - Reduce `NOTIONAL`.

- **If `avgHalfLifeMs` is high**:
  - Lower `MAX_HOLD_MS`.
  - Increase `EXIT_Z` aggressiveness.

- **If trades are rare**:
  - Lower `ENTRY_Z` or relax `MIN_CORR`.
  - Increase `WINDOW_MS` to stabilize signal.

## Decision Tree

```
Is netPnL positive?
  ├─ Yes → Is avgHalfLifeMs reasonable?
  │        ├─ Yes → Keep settings; extend duration.
  │        └─ No  → Tighten exit, reduce hold.
  └─ No  → Increase entry Z or tighten filters.
```
