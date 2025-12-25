# Execution Impl Module

This module contains the implementations of the interfaces defined in the `execution-domain` module. It supports different execution environments, such as live trading via Binance or simulated trading.

## Implementations

### Binance Execution

- **BinanceExecutionGateway**: Implements order placement using the `BinanceTestNetApiService` from the `:network` module.
- **BinanceAccountStateRepository**: Fetches account balances from the Binance API.

### Simulated Execution

- **SimExecutionGateway**: A placeholder for a future simulated execution engine (paper trading).
- **SimAccountStateRepository**: A placeholder for simulated account state.

## Dependency Injection

- **ExecutionImplModule**: Koin module providing bindings for `ExecutionGateway` and `AccountStateRepository`. Currently defaults to the Binance implementation.

## Dependencies

- `:execution-domain`: For the core interfaces and models.
- `:network`: For accessing the Binance API.
- `:platform`: For shared types (OrderSide, OrderType).
