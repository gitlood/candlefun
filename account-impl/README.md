# Account Impl Module

This module contains the implementation of the account management interfaces defined in `:account-domain`.

## Implementations

- **BinanceAccountStateRepository**: Fetches account balances and positions directly from the Binance API via the `:network` module.
- **InMemoryAccountStateRepository**: A simple in-memory implementation for simulation and testing purposes.

## Dependencies

- `:account-domain`: For the core interfaces and models.
- `:network`: For accessing the Binance API.
- `:platform`: For shared utilities.
