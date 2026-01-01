# Platform Module

This module contains shared logic, utilities, and base models used across the entire application. It serves as a foundation for other feature modules.

## Contents

- **Domain Models**: Shared enumerations and data classes (e.g., `OrderSide`, `OrderType`, `Symbol`).
- **Extensions**: Kotlin extension functions for common operations.
- **Base Classes**: Base classes for ViewModels or other architectural components.
- **Utils**: General purpose utility functions (logging, date formatting, etc.).

## Usage

This module is a dependency for most other modules (`:network`, `:features`, etc.) to ensure consistency in data types and shared utilities.
