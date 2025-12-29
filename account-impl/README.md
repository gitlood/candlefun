# Account Impl Module

This module provides the CSV-backed wallet and inventory helpers that the strategy runners use for persistence and reporting.

## Features

- **InventoryWalletConfig**: Controls the wallet CSV path (`AVELLANEDA_WALLET_PATH`), auto-persist cadence, and file creation logic.
- **CsvWalletStore**: Reads/writes wallet rows with columns `asset,free,locked,avgPrice,realizedPnl,unrealizedPnl`.
- **CsvInventoryStateRepository**: Implements `InventoryStateRepository` with mutexed state, fill application logic, mark price updates, and optional background persistence.
- **FuturesUserStreamInventoryBridge**: Hooks into Binance user stream snapshots (used in live/testnet runners) to keep the CSV wallet aligned with exchange positions.
- **DI module**: `accountImplModule` wires the config, wallet store, and repository for Koin consumers.

## Build & Test

- Run the module tests with `./gradlew :account-impl:test` to exercise wallet persistence logic.
