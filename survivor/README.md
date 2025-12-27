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
- `ENTRY_FUNDING`, `EXIT_FUNDING`
- `BASIS_STOP_PCT`
- `MAX_VOL`, `MAX_SPREAD_PCT`
- `MAX_OI_JUMP_PCT`, `OI_WINDOW_MS`
- `MAX_HOLD_MS`, `ORDER_TTL_MS`
- `ORDER_QTY`
- `MAKER_FEE_PCT`, `TAKER_FEE_PCT`
- `BORROW_FEE_PCT_DAY`
- `LOG_SIGNALS`
