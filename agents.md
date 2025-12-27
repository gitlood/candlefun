# Trading Agents & Strategies

This document details the five algorithmic trading agents (strategies) and the central portfolio engine designed for the CandleFun application.

## 1. Adaptive Market Making (Avellaneda-style)

**Goal:** Capture the bid-ask spread while neutralizing inventory risk and avoiding toxic flow.

### Logic
*   **Quoting:** Calculates optimal bid/ask prices based on:
    *   **Mid Price**: `(BestBid + BestAsk) / 2`
    *   **Inventory Skew**: Adjusts prices to discourage accumulation of inventory beyond target limits.
    *   **Volatility**: Widens spreads during high volatility to compensate for increased risk.
*   **Toxicity Gating**:
    *   Monitors `AdvBps` (Adverse Selection Basis Points). If the price moves against a fill immediately, it indicates "toxic" informed flow.
    *   **Action**: If toxicity spikes, the agent widens quotes significantly or pauses quoting ("turtle mode").
*   **Microprice**: Uses order book imbalance to adjust the theoretical mid-price (e.g., if book is heavy on bid side, true price is higher).

### KPIs
*   Realized Spread (PnL per trade after fees)
*   Adverse Selection (Price move 1s/5s after fill)
*   Inventory Drift (Deviation from target)

---

## 2. OFI Scalper (Order-Flow Imbalance)

**Goal:** Profiting from short-term price pressure indicated by the order book flow.

### Logic
*   **Signal**: Calculates Order Flow Imbalance (OFI) over short windows (e.g., 200ms - 2s).
    *   `OFI = (BidQty_t - BidQty_t-1) - (AskQty_t - AskQty_t-1)` (simplified).
*   **Trigger**:
    *   If OFI is extremely positive (strong buying pressure) AND depth is stable -> **Buy**.
    *   If OFI is extremely negative (strong selling pressure) AND depth is stable -> **Sell**.
*   **Execution**:
    *   Typically uses passive limit orders at the best bid/ask to earn the spread rebate, or aggressive limit orders if the expected move covers the taker fee.
*   **Regime**: Works best in "Microstructure" regimes; disabled during liquidity vacuums.

---

## 3. Funding + Basis Carry ("Survivor")

**Goal:** Earn funding rates and basis convergence with delta-neutral positioning.

### Logic
*   **Concept**: Crypto perpetual futures trade at a premium/discount to spot. This "basis" drives the funding rate paid every 8 hours.
*   **Action**:
    *   **Long Funding**: If Funding > 0 (Longs pay Shorts), Sell Perp + Buy Spot.
    *   **Short Funding**: If Funding < 0 (Shorts pay Longs), Buy Perp + Sell Spot.
*   **Risk Management**:
    *   Monitors "Basis Risk" (divergence between Spot and Perp prices).
    *   Maintains strict delta neutrality.
    *   Closes positions if basis blows out or volatility becomes extreme.

---

## 4. Liquidity Vacuum Breakout

**Goal:** Capitalize on rapid price movements caused by "air gaps" in the order book.

### Logic
*   **Detection**:
    *   **Depth Collapse**: Liquidity near the spread vanishes rapidly.
    *   **Spread Widening**: The difference between bid and ask expands.
    *   **Trade Burst**: A spike in aggressive trades in one direction.
*   **Action**: Enter aggressively (Taker) in the direction of the vacuum to catch the momentum impulse.
*   **Exit**: Very fast time-based or trailing stop. This is a "tail risk" strategy (small wins or small losses, occasional big wins).
*   **Relation to MM**: When this agent triggers, the **Market Maker** agent must **STOP** immediately to avoid being run over.

---

## 5. Pairs Mean Reversion (Futures Stat-Arb)

**Goal:** Exploit temporary mispricing between two highly correlated assets (e.g., BTC/ETH, SOL/AVAX).

### Logic
*   **Model**:
    *   Calculates the spread: `Spread = Price_A - (Beta * Price_B)`.
    *   Computes a Z-Score of the spread (deviation from mean).
*   **Signal**:
    *   **Z-Score > Threshold** (Spread too high): Short Asset A / Long Asset B.
    *   **Z-Score < -Threshold** (Spread too low): Long Asset A / Short Asset B.
*   **Risk**:
    *   Monitors Correlation: If correlation breaks down, exit immediately.
    *   "Trend Check": Ensure the spread is actually reverting, not trending away.

---

## 6. Portfolio Engine (The "Brain")

Strategies do not act in isolation. The Portfolio Engine coordinates them to maximize efficiency and survival.

### Responsibilities
1.  **Intent Netting**:
    *   Collects "Intents" (Buy/Sell requests) from all agents.
    *   **Example**: MM wants to Buy 1 BTC. OFI Scalper wants to Sell 0.5 BTC.
    *   **Result**: Engine routes a **Buy 0.5 BTC** order to Binance. The internal 0.5 BTC match is free (no exchange fees).
2.  **Regime Gating**:
    *   Determines the current market "Regime" (Calm, Volatile, Toxic, Trending).
    *   Enables/Disables agents accordingly.
        *   *High Volatility* -> Disable MM, Enable Vacuum.
        *   *Stable* -> Enable MM, Enable Pairs.
3.  **Risk Management**:
    *   Global Position Caps (Max total exposure).
    *   Kill Switch (Latency disconnects, massive drawdowns).
