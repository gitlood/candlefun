# CandleFun - Binance Multi-Strategy Trading Engine

CandleFun is a robust, modular algorithmic trading engine designed for the Binance ecosystem. It focuses on clean architecture, regime detection, and a portfolio of uncorrelated strategies to hunt for edge in various market conditions.

## 🚀 The Core Philosophy: "Edge-Hunting"

The goal is not to "print money" magically. The goal is to:
1.  **Build a robust engine** that handles data, execution, and risk flawlessly.
2.  **Measure edges honestly** after fees and slippage.
3.  **Scale only what survives**.

Crypto edges decay fast. We win by detecting what's working *now*, throttling what's toxic, and running a portfolio of uncorrelated edges.

## 🧠 The Stack: 5 Alphas + 1 Portfolio Layer

We aim to implement five distinct strategies using only standard Binance data (depth, trades, funding, OI, klines), coordinated by a central Portfolio Engine.

### 1. Adaptive Market Making (Avellaneda-style)
*   **Goal**: Capture spread when flow is not toxic.
*   **Key Mechanics**:
    *   **Toxicity Gates**: Widen quotes or stop when adverse selection spikes.
    *   **Inventory Skew**: Adjust prices to neutralize inventory drift.
    *   **Microprice Skew**: Lean on short-term order book imbalances.
*   **Alpha**: Providing liquidity to uninformed flow while dodging toxic flow.

### 2. OFI Scalper (Order-Flow Imbalance)
*   **Goal**: Scalp tiny, predictable mid-price moves caused by short-term pressure.
*   **Key Mechanics**:
    *   Computes Order Flow Imbalance (OFI) over short windows (200ms-2s).
    *   Enters passively or aggressively when OFI is extreme but depth is stable.
*   **Alpha**: Short-term microstructure predictive power.

### 3. Funding + Basis Carry ("Survivor")
*   **Goal**: Capture funding payments and basis normalization (delta-neutral).
*   **Key Mechanics**:
    *   Long Spot / Short Perp (or vice versa) to capture funding rates.
    *   Entry/Exit based on funding extremes and basis stability.
*   **Alpha**: Structural market inefficiencies and leverage demand.

### 4. Liquidity Vacuum Breakout
*   **Goal**: Catch fast price jumps when the order book thins out ("air-gaps").
*   **Key Mechanics**:
    *   Detects rapid depth collapse + spread widening + trade bursts.
    *   Enters aggressively to catch the impulse.
*   **Alpha**: Momentum during liquidity crises.

### 5. Pairs Mean Reversion (Futures Stat-Arb)
*   **Goal**: Exploit relative mispricing between correlated assets (e.g., BTC/ETH).
*   **Key Mechanics**:
    *   Models the spread between two cointegrated assets.
    *   Shorts the "rich" asset and longs the "cheap" asset when Z-score deviates.
*   **Alpha**: Mean reversion of relative value.

---

### 🛡️ The "Hidden" 6th: Portfolio Engine (Netting & Execution)

Strategies **do not** place orders. They output **Intents** (Desired Delta, Urgency, etc.). The Engine Layer:

1.  **Nets Intents**: If Strategy A buys and Strategy B sells, they cross internally (saving fees).
2.  **Regime Gating**: Enables/disables strategies based on market conditions (e.g., Volatility, Toxicity).
3.  **Risk Management**: Enforces global position caps, leverage limits, and kill switches.
4.  **Execution Routing**: Smartly routes the net delta to Binance (Maker vs. Taker logic).

## 🏗️ Project Structure

The project is modularized to separate concerns:

*   **`:network`**: Binance API (REST + WebSocket), DTOs, and Candle Store.
*   **`:marketdata-domain` / `:marketdata-impl`**: Market data repositories and abstractions.
*   **`:execution-domain` / `:execution-impl`**: Order execution interfaces and implementations (Live vs. Sim).
*   **`:account-domain` / `:account-impl`**: Portfolio state, inventory tracking.
*   **`:platform`**: Shared utilities, domain models (Time, Math, Logging).
*   **`:features`**: High-level application logic (Foreground services, UI bridges).
*   **Strategy Modules**:
    *   `:avellaneda-mm`: Adaptive Market Maker implementation.
    *   `:ofi-kukanov`: OFI Scalper implementation.
    *   `:survivor`: Funding/Basis Carry implementation.
    *   `:vacuum`: Liquidity Vacuum implementation.
    *   `:pairs`: Pairs Trading implementation.

## 🛠️ Getting Started

1.  **Configure API Keys**: (Instructions to be added - likely local.properties or env vars).
2.  **Build**: `./gradlew assembleDebug`
3.  **Run Tests**: `./gradlew test` (Runs unit tests across all modules).

## 📊 Key Metrics (The "Truth Meter")

We measure success not just by PnL, but by:
*   **Realized Spread**: Are we actually capturing the spread after fees?
*   **Adverse Selection (AdvBps)**: Does the price move against us immediately after a fill?
*   **Inventory Drift**: Are we staying within target exposures?
*   **Fee Savings**: How much did internal netting save vs. independent bots?

## 🧾 Avellaneda Reports (CSV)

Avellaneda runners can emit per-symbol CSV snapshots for quick review and tuning.

*   **Default path**: `reports/avellaneda/avellaneda_<mode>.csv` (`backtest`, `live`, `testnet`)
*   **Env knobs**:
    *   `REPORT_ENABLED` (default `true`)
    *   `REPORT_EVERY_MS` (default `60000`)
    *   `REPORT_DIR` (directory override)
    *   `REPORT_PATH` (file override)
    *   `AVELLANEDA_REPORT_PATH` (Avellaneda-specific file override)

---
*Built with ❤️ and Kotlin.*
