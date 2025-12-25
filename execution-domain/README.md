# Execution Domain Module

This module defines the core domain entities and interfaces for order execution and account management. It is a pure Kotlin module with no Android dependencies.

## Key Components

- **ExecutionGateway**: Interface defining operations for placing, canceling, and replacing orders.
- **AccountStateRepository**: Interface for retrieving account information such as balances and fills.
- **Models**: Domain data classes like `ExecutionOrder`, `OrderRequest`, `Position`, etc.

## Purpose

The `execution-domain` module serves as the contract for any execution implementation (e.g., real Binance trading, simulated paper trading). It decouples the business logic of trading strategies from the specific implementation details of the exchange API.
