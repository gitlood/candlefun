# Market Data Impl Module

This module contains the implementations of the interfaces defined in the `marketdata-domain` module.

## Implementations

### Binance Market Data

- **BinanceMarketDataRepository**: Uses the `BinanceApiService` (Spot) from the `:network` module to fetch market data.
- **BinanceWebSocketMarketDataRepository**: Uses the WebSocket service from the `:network` module for real-time updates (if applicable).

### Simulated Market Data

- **SimMarketDataRepository**: A placeholder for simulated market data, potentially using historical data from `CandleStore` in the `:network` module.

## Dependencies

- `:marketdata-domain`: For the core interfaces and models.
- `:network`: For accessing the Binance API and data storage.
