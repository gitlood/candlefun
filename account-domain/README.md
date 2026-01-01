# Account Domain Module

This module defines the core domain entities and interfaces for managing user accounts, positions, and balances. It serves as the single source of truth for portfolio state.

## Key Components

- **Models**:
  - `Account`: Represents the user's overall account status (balances, commissions, permissions).
  - `Position`: Represents a held position in a specific symbol (for Futures/Margin).
  - `Balance`: Represents the free and locked amount of a specific asset.
  - `Price`, `Qty`, `Symbol`: Value classes to ensure type safety and consistent math.

## Purpose

The `account-domain` module abstracts the concept of a "User Account" from the specific exchange implementation. Whether the data comes from Binance, a simulation, or another source, the core logic in other modules (like Execution or Strategy) relies on these uniform definitions.

## Dependencies

- `:platform`: For shared types and utilities.
