# Trading Agents & Strategies

This document details the five algorithmic trading agents (strategies) and the central portfolio engine designed for the CandleFun application. It is intended to be implementation-ready: key parameters include defaults and units; state transitions and risk controls are explicit.

## Global Conventions
*   **Timebase**: Market data sampled at 100ms unless otherwise noted. All rolling windows are in milliseconds.
*   **Prices**: Quoted in quote currency (e.g., USD). Bps = 1/100th of 1%.
*   **Orders**: Default order types are `PostOnly` for maker and `IOC` for taker unless specified.
*   **Risk**: Per-strategy max notional and max loss are hard caps enforced by the Portfolio Engine.
*   **Cooldowns**: State transitions use hysteresis and cooldown timers to avoid flapping.
*   **Architecture**: Strategies never place orders. They output intents that the Portfolio Engine nets, allocates, and routes.

## Global Parameters (Defaults)
*   **MaxTotalNotional**: 2.0x account equity.
*   **MaxSingleStrategyNotional**: 0.6x account equity.
*   **MaxDrawdownDaily**: 3% of equity -> kill switch.
*   **MaxLatencyMs**: 250ms -> kill switch.
*   **RegimeUpdateMs**: 500ms.
*   **RegimeHysteresisMs**: 3000ms (state must persist).
*   **MinBookDepthUSD**: 50,000 within top 5 levels (else "Thin").
*   **MinSpreadBps**: 1.5 bps (else "Tight").
*   **MaxSpreadBps**: 30 bps (else "Wide").
*   **VolatilityLookbackMs**: 10,000ms.
*   **VolatilityHigh**: 2.5x median (over lookback) -> "Volatile".
*   **ToxicitySpikeAdvBps**: 5 bps within 1s after fill.
*   **KillSwitchCooldownMs**: 60,000ms.
*   **IntentCycleMs**: 200ms.
*   **FastLoopMs**: 100ms.
*   **MediumLoopMs**: 1000ms.
*   **SlowLoopMs**: 60,000ms.

## Intent-Only Strategy Interface
Each strategy produces an **Intent** (no direct orders):
*   **Desired Exposure**: target delta or inventory (signed notional).
*   **Order Preferences**: maker/taker bias, TTL, max slippage bps, urgency.
*   **Confidence**: 0.0–1.0.
*   **Risk Budget Request**: 0.0–1.0 share of portfolio risk budget.

The Portfolio Engine owns all order placement via a single router that:
*   Nets conflicts across strategies to avoid self-trading.
*   Enforces global and per-strategy risk caps.
*   Applies execution policy (maker/taker rules, TTL, throttles).

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

### Execution
*   **Order Type**: `PostOnly` at top-of-book, update cadence 200ms.
*   **Inventory Target**: 0.0 to 0.2x MaxSingleStrategyNotional; linear skew outside target band.
*   **Spread Model**: BaseSpreadBps + k_vol * vol + k_inv * inventory_skew.
*   **Turtle Mode**: If ToxicitySpikeAdvBps triggered 3 times within 30s -> pause quoting for 10s.
*   **OFI Synergy**: If OFI is strong, MM quotes smaller and wider; only quote on inventory-reducing side.

### Risk
*   **MaxInventoryNotional**: 0.4x MaxSingleStrategyNotional.
*   **Stop Quoting**: If spread > MaxSpreadBps or book "Thin".

### KPIs
*   Realized Spread (PnL per trade after fees)
*   Adverse Selection (Price move 1s/5s after fill)
*   Inventory Drift (Deviation from target)
*   Quote Fill Rate (fills / quotes posted)

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

### Execution
*   **OFI Window**: 500ms; trigger if OFI z-score > 2.5.
*   **Depth Stability**: Top-3 levels depth variation < 15% over 1s.
*   **Order Type**: `PostOnly` if expected move < taker fee + 1 bps, else `IOC`.
*   **Hold Time**: 3s max; exit on mean reversion or stop.
*   **MM Synergy**: OFI signal feeds MM skew and gating; when OFI is active, MM becomes defensive.

### Risk
*   **MaxTradeNotional**: 0.15x MaxSingleStrategyNotional.
*   **Stop Loss**: 6 bps adverse move from entry.

### KPIs
*   Win Rate
*   Avg Hold Time
*   Slippage vs Mid

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

### Execution
*   **Entry Threshold**: Funding absolute value > 10 bps/day and basis within +/- 25 bps.
*   **Rebalance**: Every 2s to maintain delta neutrality within 1%.
*   **Order Type**: Prefer passive entry; taker only if funding window < 15 min.
*   **Portfolio Role**: Acts as slow, diversifying edge; reduce others if margin is tight.

### Risk
*   **MaxBasisDivergence**: 50 bps -> exit both legs.
*   **MaxVolatility**: If vol > 3.0x median -> halt entries.

### KPIs
*   Net Funding PnL
*   Basis Drift
*   Hedge Error (delta)

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

### Execution
*   **Trigger**: Spread > MaxSpreadBps and top-3 depth collapses > 50% within 500ms.
*   **Order Type**: `IOC` in direction of trade burst.
*   **Exit**: 1s timeout or trailing stop 12 bps.
*   **MM Kill Switch**: On trigger, cancel MM quotes immediately and freeze re-quote for 10s.

### Risk
*   **MaxTradeNotional**: 0.2x MaxSingleStrategyNotional.
*   **Cooldown**: 5s after exit.

### KPIs
*   Tail Capture (PnL from top 5% moves)
*   Avg Slippage

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

### Execution
*   **Lookback**: 60m for beta and z-score; refresh beta every 5m.
*   **Entry**: |Z| > 2.0 and half-life < 30m.
*   **Exit**: Z crosses 0.5 or correlation < 0.6.
*   **Order Type**: `PostOnly` legs if spread > MinSpreadBps; else `IOC`.
*   **Portfolio Hedge**: Use to offset directional exposure from micro strategies.

### Risk
*   **MaxNotionalPerLeg**: 0.25x MaxSingleStrategyNotional.
*   **Stop Loss**: |Z| > 3.5 -> exit.

### KPIs
*   Mean Reversion Hit Rate
*   Avg Reversion Time

---

## 6. Portfolio Engine (The "Brain")

Strategies do not act in isolation. The Portfolio Engine coordinates them to maximize efficiency and survival.

### Responsibilities
1.  **Intent Netting**:
    *   Collects "Intents" (Buy/Sell requests) from all agents.
    *   **Example**: MM wants to Buy 1 BTC. OFI Scalper wants to Sell 0.5 BTC.
    *   **Result**: Engine routes a **Buy 0.5 BTC** order to Binance. The internal 0.5 BTC match is free (no exchange fees).
    *   **Rule**: Net by symbol every IntentCycleMs before routing.
2.  **Regime Gating**:
    *   Determines the current market "Regime" (Calm, Volatile, Toxic, Trending).
    *   Enables/Disables agents accordingly.
        *   *High Volatility* -> Disable MM, Enable Vacuum.
        *   *Stable* -> Enable MM, Enable Pairs.
3.  **Risk Management**:
    *   Global Position Caps (Max total exposure).
    *   Kill Switch (Latency disconnects, massive drawdowns).
4.  **Edge Scoring**:
    *   Maintains a rolling edge score per strategy; throttles or disables if score falls below threshold.
5.  **Risk Budget Allocation**:
    *   Allocates risk units by score and caps; normalizes to total risk budget.

### Regime Definitions
*   **Calm**: Spread < MinSpreadBps and vol < 1.2x median and depth stable.
*   **Volatile**: vol > VolatilityHigh or spread > MaxSpreadBps.
*   **Toxic**: ToxicitySpikeAdvBps triggered twice within 10s.
*   **Trending**: 1m return > 1.5x 10m median return.
*   **Carry Opportunity**: Funding extreme and stable, basis contained, vol not spiking.
*   **Pair Stable**: Correlation high and spread stationary.
*   **Pair Unstable**: Correlation breaks or spread trends.

### Agent Enable Matrix
*   **Calm**: MM ON, Pairs ON, OFI reduced, Vacuum OFF, Survivor ON.
*   **Volatile**: MM OFF, Pairs OFF, OFI ON, Vacuum ON, Survivor ON.
*   **Toxic**: MM OFF, OFI selective, Vacuum ON (clean signals only), Survivor ON (reduced size).
*   **Trending**: MM OFF, Pairs OFF, OFI ON, Vacuum ON, Survivor ON.
*   **Carry Opportunity**: Survivor ON, others reduced if margin tight.
*   **Pair Stable**: Pairs ON.
*   **Pair Unstable**: Pairs OFF immediately.

### Edge Score (Rolling)
*   **MM**: realized spread after fees minus adverse selection minus inventory penalty.
*   **OFI**: hit-rate x avg move minus slippage and fees.
*   **Vacuum**: win-rate x payoff minus tail-loss frequency.
*   **Carry**: realized funding capture minus basis variance and liquidation risk.
*   **Pairs**: convergence rate minus break frequency.
*   **Rule**: If score < threshold for 3 consecutive windows -> throttle; if score < hard floor -> disable.

### Risk Budget Allocation
*   **Portfolio Risk Budget**: MaxTotalNotional with drawdown constraints.
*   **Caps**: MM 30%, OFI 20%, Vacuum 15%, Carry 40%, Pairs 25%.
*   **Allocator**: weight_i = clamp(score_i, 0, 1) ^ p, with p=2.
*   **Normalize**: Apply caps, renormalize to total risk budget.

### Implementation Loops
*   **Fast Loop (50–250ms)**: book/trades -> micro features -> MM/OFI/Vacuum intents.
*   **Medium Loop (1–5s)**: volatility regime, adverse selection stats, liquidity metrics.
*   **Slow Loop (1–5m)**: funding/basis checks, pairs beta/zscore, universe selection.
*   **Shared State**: All signals write to `MarketState`. Strategies only read and emit intents.

### Kill Switch
*   Trigger if any: MaxDrawdownDaily breached, MaxLatencyMs breached, or exchange disconnect > 5s.
*   Action: Cancel all orders, flatten positions, enter cooldown for KillSwitchCooldownMs.
