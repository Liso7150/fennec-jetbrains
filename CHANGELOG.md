# Changelog

All notable changes to fennec-jetbrains, the Air agent plugin for JetBrains IDEs and the
Rust sidecar it spawns.

The sections below carry the published tag each change actually shipped in
(`Liso7150/fennec-jetbrains`, `v0.3.0` … `v0.9.0`). An earlier revision of this
file numbered the same history on a 1.x/2.x ladder that was never published;
those tags still exist locally (`v1.2.0` … `v2.0.0`) and each one points at the
same commit as a published `v0.x` tag — except `v1.2.0`, which marks a
pre-release commit that shipped inside `v0.3.0`. The first release here
was `0.3.0`. Dates are the tag publish dates.

## [Unreleased] — 1.0.0 in preparation

Nothing at 1.0.0 is published. The newest release here is `v0.9.0`
(2026-11-11T19:29Z). Measured on the working tree: 73 Rust sidecar tests pass
(`cargo test --offline` in `sidecar/`, 2026-11-15) and 34 Kotlin headless
checks run in `src/test/` — `./run-tests.sh` runs both.

### Added
- The Rust sidecar the plugin spawns is tracked in this repo
- README hero published

## [0.9.0] - 2026-11-11

### Changed
- The plugin registers the faster agent, in Java

## [0.8.0] - 2026-11-11

### Added
- Better model auto-discovery and setup guidance in the bridge
- Air-managed binary distribution for install and update
- The plugin renamed to Fennec, with its icon and package name
- The agent renamed to Fennec, with platform variants

### Changed
- Registrar only: the proxy bridge is gone
- The agent is marked bundled/local for Air

### Fixed
- Registry distribution for local install, and the published archive's SHA256

## [0.7.1] - 2026-11-11

Version bump only.

## [0.7.0] - 2026-11-11

Version bump only.

## [0.6.0] - 2026-11-11

Version bump only.

## [0.5.0] - 2026-11-11

Version bump only.

## [0.4.0] - 2026-11-11

### Changed
- The Air bridge runs on python from PATH

## [0.3.0] - 2026-11-10

The first published release. It carries the pre-tag history below.

### Changed
- Rebrand the plugin and the Air bridge to Fennec
- The pre-rebrand colibri entry is dropped when the registry is merged
- The configured engine URL is bound in the Air bridge

### Added
- Colibri Code published as an Air Agents-page card
- Registered as an Air agent, with the engine stopped when idle
- The engine starts with the configured GPU backend
- Reasoning streams into the chat panel
