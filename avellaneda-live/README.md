## Avellaneda Live (Paper + Testnet Execution)

This module runs the Avellaneda strategy against live market data, either with simulated fills (paper) or real orders on Binance futures testnet.

### Strategy Integration

- **Strategy**: `AvellanedaMmStrategy` from `:avellaneda-mm`.
- **Market data**: live `MarketState` from spot/futures repositories.
- **Execution**:
  - **Paper**: `SimExecutionGateway` + `ConservativeFillSimulator`.
  - **Testnet**: `ExecutionGateway` wired to Binance futures testnet.
- **Inventory**: `CsvInventoryStateRepository` persists wallet state to `avellaneda_wallet.csv`.
- **Reporting**: CSV snapshots for per-symbol PnL, fills, fees, and adverse selection.

### Reporting (CSV)

Default paths:
- `reports/avellaneda/avellaneda_live.csv`
- `reports/avellaneda/avellaneda_testnet.csv`

Env knobs:
- `REPORT_ENABLED` (default `true`)
- `REPORT_EVERY_MS` (default `60000`)
- `REPORT_DIR` (directory override)
- `REPORT_PATH` (file override)
- `AVELLANEDA_REPORT_PATH` (Avellaneda-specific file override)

Columns (per row):
`ts_ms,mode,symbol,mid,qty,avg,unrealized_pnl,realized_pnl,net_pnl,pnl_pct,exposure,fills,maker_fills,taker_fills,total_fees,total_notional,adv_1s_bps,adv_5s_bps`

### How To Interpret The Report (AI Notes)

- **Primary signals**:
  - `net_pnl` and `pnl_pct`: profitability per symbol and total.
  - `adv_1s_bps` / `adv_5s_bps`: adverse selection; negative means toxic fills.
  - `fills`: activity; too high implies overtrading, too low implies no edge capture.
  - `total_fees`: should not dominate `net_pnl`; fee drag is a common failure.
  - `exposure` and `unrealized_pnl`: inventory risk and drift under live conditions.

- **Healthy profile**:
  - `net_pnl` trending up with modest adverse bps.
  - Fills are steady but not excessive; `exposure` stays bounded.
  - `realized_pnl` improves over time, not only unrealized swings.

- **Common failure modes**:
  - **Testnet exchange filters**: zero fills, frequent order rejections.
    - Response: tighten symbol list, confirm tick/step sizes, widen spreads slightly.
  - **Adverse selection spike**: negative `adv_*_bps` and flat/negative PnL.
    - Response: increase `MIN_AVG_SPREAD_PCT`, switch `QUOTE_STYLE=WIDEN`.
  - **Fee churn**: high fills, near-zero `net_pnl`, rising fees.
    - Response: widen spread targets and reduce refresh rate.
  - **Inventory drift**: large `exposure`, unstable `unrealized_pnl`.
    - Response: increase `INVENTORY_SKEW`, reduce `MAX_INVENTORY`.

### AI Feedback Packet (Recommended)

Paste the last 10–20 rows from:

- `reports/avellaneda/avellaneda_live.csv`
- `reports/avellaneda/avellaneda_testnet.csv`

Include:
- Exact env overrides used.
- Duration of the run.
- Any exchange errors or warnings.

### Tuning Playbook (Live/Testnet)

- **If orders are rejected or no fills**:
  - Keep `SYMBOLS` to liquid pairs only.
  - Use `QUOTE_STYLE=WIDEN` to stay inside price bands.
  - Verify `ORDER_QTY` meets `minQty` and notional filters.

- **If fills are frequent but `net_pnl` ~ 0**:
  - Increase `MIN_AVG_SPREAD_PCT` and `ADAPTIVE_SPREAD_TARGET_BPS`.
  - Increase `QUOTE_REFRESH_MS` to reduce churn.

- **If `adv_*_bps` is negative**:
  - Switch `QUOTE_STYLE=WIDEN`.
  - Raise `MIN_SPREAD_PCT` and `MIN_AVG_SPREAD_PCT`.
  - Reduce `MAX_INVENTORY` to limit toxic exposure.

- **If inventory drifts**:
  - Increase `INVENTORY_SKEW`.
  - Reduce `MAX_INVENTORY`.
  - Ensure mark prices update (no stale market data).

- **If `net_pnl` is positive but unstable**:
  - Lower `ORDER_QTY`.
  - Reduce `QUOTE_STYLE` aggressiveness (IMPROVE -> JOIN -> WIDEN).
  - Tighten `MAX_SPREAD_PCT` to avoid huge gaps.

### Decision Tree (Live/Testnet)

```
Are orders accepted?
  ├─ No  → Limit symbols, raise min qty, use QUOTE_STYLE=WIDEN.
  └─ Yes → Is net_pnl positive?
           ├─ Yes → Is adv_*_bps near 0/positive?
           │        ├─ Yes → Keep settings; extend duration.
           │        └─ No  → Widen spreads, reduce size.
           └─ No  → Are fills very high?
                    ├─ Yes → Raise spreads, slow refresh.
                    └─ No  → Tighten spreads, JOIN/IMPROVE.
```

### Live Workflow (Timing + Intent)

1) **Paper run**
   - **Intent**: validate strategy behavior and quote flow without exchange risk.
   - **Typical duration**: 30–120 minutes for signal sanity.

2) **Testnet run**
   - **Intent**: verify real exchange constraints (filters, latency, fees).
   - **Typical duration**: 4–12 hours for robust fill/PnL signal.

### Paper Run Command

```sh
MARKETDATA_SOURCE=FUTURES \
SYMBOLS=BTCUSDT,ETHUSDT \
REPORT_EVERY_MS=60000 \
./gradlew :avellaneda-live:run
```

### Testnet Run Command

```sh
export BINANCE_TESTNET_API_KEY="..."
export BINANCE_TESTNET_SECRET_KEY="..."

MARKETDATA_SOURCE=FUTURES \
SYMBOLS=BTCUSDT,ETHUSDT \
REPORT_EVERY_MS=60000 \
./gradlew :avellaneda-live:runTestnet
```

### Key Env Overrides

- `SYMBOLS`, `TOP_N`, `MIN_QUOTE_VOLUME`, `MIN_TRADES`, `QUOTE_ASSETS`
- `TICK_MS`, `DEPTH_LEVELS`, `DEPTH_SPEED_MS`, `SNAPSHOT_DEPTH`
- `ORDER_QTY`, `MAX_INVENTORY`, `INVENTORY_SKEW`
- `MIN_SPREAD_PCT`, `MIN_AVG_SPREAD_PCT`, `MAX_AVG_SPREAD_PCT`
- `ADAPTIVE_SPREAD_TARGET_BPS`, `ADAPTIVE_SPREAD_UPDATE_MS`
- `QUOTE_STYLE` (`JOIN`, `IMPROVE`, `WIDEN`)
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`
- `FILL_POLL_MS`, `CLEAN_START`, `RESET_WALLET`
