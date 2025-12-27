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
