# Execution Domain Module

This module defines the core contracts that the execution stack, portfolio engine, and strategies rely on. It is an exchange-agnostic Kotlin module with zero Android dependencies.

## Key Interfaces

- `ExecutionGateway`: Commands to place/cancel/replace orders plus helpers to query open orders, balances, and positions.
- `AccountStateRepository`: Abstracts balance/fill exposure access for reporting and risk tracking.
- `StrategyContext` / `IntentStrategy`: Strategies implement `IntentStrategy.onMarketState(...)` to emit declarative `StrategyIntent`s from live `MarketState`.
- `StrategyIntent`: Contains `desiredDelta`, urgency, `maxSlippageBps`, maker/taker preference, confidence, reason, and optional `riskBudgetRequest` fields.
- `IntentAllocation`, `NettingSummary`, and `RoutingDecision`: Describe how intents are netted, scaled by confidence/risk, and turned into real orders.
- `RiskBudget` / `StrategyRegimeState`: Control per-strategy caps and the regime gating information consumed by the Portfolio Engine.

## Workflow

1. Strategies produce `StrategyIntent`s populated with `symbol`, `desiredDelta`, `confidence`, etc.
2. `PortfolioEngine` nets intents per symbol, applies `StrategyRegimeState` gates, and allocates risk via `RiskBudget`.
3. Resulting `RoutingDecision`s are handed to `ExecutionGateway` implementations, and `IntentAllocator` tracks edge scores.

## Build & Test

- Run the domain tests to validate intent helpers: `./gradlew :execution-domain:test`.
