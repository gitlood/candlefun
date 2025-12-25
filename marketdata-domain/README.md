# Market Data Domain Module

This module defines the core domain entities and interfaces for market data retrieval. It is a pure Kotlin module with no Android dependencies.

## Key Components

- **MarketDataRepository**: Interface for fetching market data such as klines (candlesticks), ticker information, and order books.
- **Models**: Domain data classes like `Kline`, `Ticker`, `OrderBook`, etc.

## Purpose

The `marketdata-domain` module acts as an abstraction layer for market data. It allows the rest of the application to interact with market data without knowing the source (e.g., live Binance API, historical database, or simulation).
