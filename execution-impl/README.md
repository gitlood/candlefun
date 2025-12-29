# Execution Impl Module

This module wires the domain contracts to concrete gateways and repositories, providing both live (Binance testnet) and simulated implementations for strategy runners.

## Implementations

- **Spot/Testnet**:
  - `BinanceExecutionGateway` + `BinanceAccountStateRepository` for REST/WebSocket trading via `:network`.
- **Futures Testnet**:
  - `FuturesExecutionGateway` + `FuturesAccountStateRepository` (Binance futures client) since the strategy runners often target perpetual contracts.
- **Simulation**:
  - `SimExecutionGateway` uses `ConservativeFillSimulator`, queues, and fill tracking to mirror maker/taker behavior.
  - `SimAccountStateRepository` keeps balances, fills, and exposures in-memory for backtests and paper runs.

## DI & Configuration

- `ExecutionImplModule` (Koin) wires `ExecutionGateway`, `AccountStateRepository`, `PortfolioEngine`, `EdgeScoreEngine`, and `IntentAllocator` for runners such as `superbot`, `avellaneda-live`, `vacuum`, `ofi`, and `pairs`. It defaults to the simulator stack but can be overridden with `FuturesExecutionGateway` plus `BinanceExecutionGateway`/`BinanceAccountStateRepository`.
- `EnvExecutionCredentialsProvider` and `RiskBudgetEnv` read env vars to bootstrap credentials, risk shares, and kill-switch knobs consumed by `ExecutionPolicy`, `RiskAllocator`, and the `PortfolioEngine`.

## Build & Test

- Run the implementation tests: `./gradlew :execution-impl:test`.
