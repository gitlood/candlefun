# Network Module

This module handles all network communication with the Binance API. It includes implementations for both the public and private APIs, as well as WebSocket connections for real-time data.

## Features

- **Binance API Integration**: Provides access to Binance REST APIs (Spot, TestNet).
- **WebSocket Client**: Handles real-time market data streams (Tickers, Klines, Depth).
- **Data Mappers**: Converts DTOs (Data Transfer Objects) to Domain models.
- **Candle Collector**: A utility to collect and store historical candle data.

## Key Components

- `BinanceApiServiceImpl`: Implementation of the REST API calls.
- `BinanceWebSocketServiceImpl`: Manages WebSocket connections and subscriptions.
- `BinanceSigner`: Utility for signing requests with HMAC SHA256.
- `CandleStore`: Local storage for collected candle data.

## Testing

The module includes comprehensive unit tests for:
- DTO Mappers
- Repository Implementations
- API Security (Signing)
- Candle Storage

To run tests:
`./gradlew :network:test`
