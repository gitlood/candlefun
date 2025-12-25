# Features Module

This module contains the high-level feature implementations for the CandleFun application. It bridges the data layer (network/repository) with the UI and other application logic.

## Key Features

- **Candle Collector Service**: A foreground service that collects historical candle data for specified symbols.
- **Market Data Visualization**: (Planned) Components for displaying charts and order books.
- **Trading Interface**: (Planned) Components for placing and managing orders.

## Architecture

This module follows a Clean Architecture approach, utilizing Use Cases and ViewModels (where applicable) to separate concerns.

- **Use Cases**: Encapsulate specific business logic.
- **Services**: Long-running background operations (e.g., data collection).

## Dependencies

- `:network`: For accessing Binance APIs and data models.
- `:platform`: For shared utilities and domain models.
