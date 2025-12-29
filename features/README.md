# Features Module

This module currently hosts experimental feature utilities (e.g., DeepLOB window builders and modeling helpers) that support analytics and signal research for CandleFun.

## Contents

- `com.example.features.deeplob.DeepLobWindowBuilder`: Builds normalized LOB windows from raw order book snapshots.
- `com.example.features.deeplob.DeepLobModels`: Lightweight data representations for the DeepLOB pipeline.
- `com.example.features.ai.ExportFeaturesCode`: Helper script for exporting feature code snippets for downstream tooling.

## Build & Test

- Run `./gradlew :features:test` to exercise DeepLOB serialization and window building helpers.

## Dependencies

- `:network` (for market/state models if needed)
- `:platform` for shared math/time extensions.
