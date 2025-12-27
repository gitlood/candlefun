## Avellaneda MM (Backtest + Core Strategy)

This module contains the Avellaneda-style market-making strategy and the backtest runner. It operates on `MarketState` snapshots and drives quotes via the execution gateway.

### Strategy Overview

- **Objective**: Capture spread while controlling inventory and avoiding toxic flow.
- **Inputs**: `MarketState` (mid, spread, best bid/ask, trades, short-term vol/OFI).
- **Core mechanics**:
  - **Inventory skew**: Shift quotes to mean-revert inventory.
  - **Spread gates**: Enforce min/avg spread thresholds before quoting.
  - **Quote style**: JOIN / IMPROVE / WIDEN relative to best bid/ask.
  - **Adverse selection tracking**: Measure post-fill drift for gating/diagnostics.

### Integration Points

- **Market data replay**: `MarketStateReplayer` reads `marketstate.csv` and emits snapshots.
- **Execution**: `SimExecutionGateway` + `ConservativeFillSimulator` for fills and fees.
- **Inventory**: `CsvInventoryStateRepository` writes wallet state to `avellaneda_wallet.csv`.
- **Reporting**: CSV snapshots for per-symbol PnL, fills, fees, and adverse selection.

### Reporting (CSV)

Default path: `reports/avellaneda/avellaneda_backtest.csv`

Env knobs:
- `REPORT_ENABLED` (default `true`)
- `REPORT_EVERY_MS` (default `60000`)
- `REPORT_TRUNCATE` (default `true`)
- `REPORT_TIMESTAMPED` (default `false`)
- `REPORT_DIR` (directory override)
- `REPORT_PATH` (file override)
- `AVELLANEDA_REPORT_PATH` (Avellaneda-specific file override)

Manifest logging (JSONL):
- `MANIFEST_ENABLED` (default `true`)
- `MANIFEST_PATH` (file override)
- `MANIFEST_TIMESTAMPED` (default `false`)
- `REPORT_DIR` (directory override)
- `RUN_ID`, `RUN_NOTES`

Columns (per row):
`ts_ms,mode,symbol,mid,qty,avg,unrealized_pnl,realized_pnl,net_pnl,pnl_pct,exposure,fills,maker_fills,taker_fills,total_fees,total_notional,adv_1s_bps,adv_5s_bps`

### How To Interpret The Report (AI Notes)

- **Primary signals**:
  - `net_pnl` and `pnl_pct`: overall profitability per symbol and total.
  - `realized_pnl`: cashflow from completed round-trips; should trend positive.
  - `unrealized_pnl`: inventory risk; large swings imply skew/quote tuning needed.
  - `adv_1s_bps` / `adv_5s_bps`: post-fill drift; consistently negative means toxic flow.
  - `fills`, `maker_fills`, `taker_fills`: activity and execution mix; high taker share is costly.
  - `total_fees` vs `total_notional`: fee drag relative to volume.

- **Healthy profile**:
  - `net_pnl` positive, `realized_pnl` positive, adverse bps near 0 or slightly positive.
  - Fills regular but not excessive; taker fills near zero in backtest.
  - `pnl_pct` stable and not driven by large unrealized swings.

- **Common failure modes**:
  - **Toxic flow**: `adv_*_bps` strongly negative, `net_pnl` drifting down.
    - Response: widen spreads, reduce inventory skew, increase min avg spread.
  - **Overtrading**: high `fills` with flat/negative `net_pnl`, fees large.
    - Response: raise `MIN_AVG_SPREAD_PCT`, lower `QUOTE_REFRESH_MS`, or widen `QUOTE_STYLE`.
  - **Inventory drift**: large `exposure` and volatile `unrealized_pnl`.
    - Response: increase `INVENTORY_SKEW` or reduce `MAX_INVENTORY`.
  - **Too passive**: very low fills and flat `net_pnl`.
    - Response: `QUOTE_STYLE=JOIN` or `IMPROVE`, lower min spread gates.

### AI Feedback Packet (Recommended)

Paste the last 10–20 rows and include:

- The commands/params used (symbols, quote style, spreads, fees).
- The run window length and `REPLAY_SPEEDUP`.
- Any anomalies (e.g., no fills, sudden PnL spikes).

### Tuning Playbook (Backtest)

- **If `net_pnl` < 0 and `adv_*_bps` is strongly negative**:
  - Raise `MIN_AVG_SPREAD_PCT` (e.g., +0.0002 to +0.0005).
  - Set `QUOTE_STYLE=WIDEN`.
  - Reduce `ORDER_QTY` or `MAX_INVENTORY` to limit toxic exposure.

- **If `fills` are very high but `net_pnl` ~ 0**:
  - Increase `ADAPTIVE_SPREAD_TARGET_BPS` (e.g., +2 to +4 bps).
  - Increase `QUOTE_REFRESH_MS` (less churn).
  - Raise `MIN_SPREAD_PCT` slightly.

- **If `fills` are too low and `net_pnl` flat**:
  - Set `QUOTE_STYLE=JOIN` or `IMPROVE`.
  - Reduce `MIN_AVG_SPREAD_PCT` or `MIN_SPREAD_PCT`.
  - Lower `ADAPTIVE_SPREAD_TARGET_BPS`.

- **If `unrealized_pnl` swings dominate**:
  - Increase `INVENTORY_SKEW`.
  - Reduce `MAX_INVENTORY`.
  - Tighten `MAX_SPREAD_PCT` if quotes are too wide.

- **If `adv_*_bps` positive but `net_pnl` still low**:
  - Fees likely dominate; reduce taker exposure and widen spread targets.
  - Confirm `MAKER_FEE_PCT`/`TAKER_FEE_PCT` are realistic.

### Decision Tree (Backtest)

```
Is net_pnl positive?
  ├─ Yes → Are adv_*_bps near 0 or positive?
  │        ├─ Yes → Keep settings; test a longer window.
  │        └─ No  → Widen spreads, reduce size, increase skew.
  └─ No  → Are fills very high?
           ├─ Yes → Raise min/avg spreads, slow refresh.
           └─ No  → Tighten spreads, set QUOTE_STYLE=JOIN/IMPROVE.
```

### Backtest Workflow (Timing + Intent)

1) **Record market data**
   - **Intent**: capture real market microstructure for replay.
   - **Typical duration**: 1–6 hours (short for quick iteration, long for robust stats).

2) **Backtest replay**
   - **Intent**: evaluate strategy performance on the recorded window.
   - **Runtime**: matches CSV span at `REPLAY_SPEEDUP=1`.
   - **Speedup**: `REPLAY_SPEEDUP=100` runs ~100x faster.

### Backtest Command (example)

```sh
SYMBOLS=BTCUSDT,ETHUSDT \
MARKETSTATE_CSV="/Users/thelood/AndroidStudioProjects/CandleFun/marketstate.csv" \
REPORT_EVERY_MS=60000 \
./gradlew :avellaneda-mm:run
```

### Key Env Overrides

- `ORDER_QTY`, `MAX_INVENTORY`, `INVENTORY_SKEW`
- `MIN_SPREAD_PCT`, `MIN_AVG_SPREAD_PCT`, `MAX_AVG_SPREAD_PCT`
- `ADAPTIVE_SPREAD_TARGET_BPS`, `ADAPTIVE_SPREAD_UPDATE_MS`
- `QUOTE_STYLE` (`JOIN`, `IMPROVE`, `WIDEN`)
- `REPLAY_SPEEDUP`
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`
