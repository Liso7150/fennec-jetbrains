# Changelog

All notable changes to fennec-jetbrains, the Air agent plugin for JetBrains IDEs and the
Rust sidecar it spawns.

The sections below carry the published tag each change actually shipped in
(`Liso7150/fennec-jetbrains`, `v0.3.0` … `v1.0.0`). An earlier revision of this
file numbered the same history on a 1.x/2.x ladder that was never published;
those tags still exist locally (`v1.2.0` … `v2.0.0`) and each one points at the
same commit as a published `v0.x` tag — except `v1.2.0`, which marks a
pre-release commit that shipped inside `v0.3.0`. The first release here
was `0.3.0`. Dates are the tag publish dates.

## [1.0.0] - 2026-11-24
Version 1.0.0 is the current release; the plugin, its Rust sidecar, and the packaged ZIP are versioned 1.0.0.
Test counts come from the results hub
(`benchmarks/artifacts/hub/RESULTS.json`, updated 2026-11-24T13:27:28Z): the
`jetbrains-rust` lane reports **80 passed / 0 failed** for `cargo test --offline`
in `sidecar/`, and the `jetbrains-kotlin` lane **46 passed / 0 failed** for
`bash ./run-tests.sh`; `./run-tests.sh` runs both suites. Suite-wide totals live
in that hub, not here.

### Added
- The Rust sidecar the plugin spawns is tracked in this repo
- README hero published
- The plugin is written in Kotlin (it was Java through `0.9.0`, `e3f84b3`); the
  headless Kotlin test harness runs it without an IDE
- An update check against this repository's GitHub releases, off by default per
  version per IDE run, with an experimental channel that reports the trunk head.
  Tools → Fennec decides whether it runs and on which channel

### Changed
- The packaged plugin directory and jar are named `fennec-jetbrains`, matching
  the repository (they were inherited as `fennec-desktop`)
- `build-plugin.sh` resolves Kotlin from PATH or `FENNEC_KOTLINC`, detects the IDE
  and Rust host target instead of hardcoding this machine, and packages only the
  configured sidecar target

### Fixed
- The version compare is numeric per component, so `1.10.0` no longer ranks
  below `1.9.0`, and a tag that only differs by a pre-release suffix is not an update
- The check reports the release page instead of promising "restart your IDE to
  apply the update": a plugin is replaced by the IDE's own installer, never by itself
- The experimental channel reads `main`, which is this repository's trunk
- `"Fennec Updates"` is registered as a notification group, so a found update can
  actually be shown
- The Fennec tool window is registered in `plugin.xml`. `FennecToolWindowFactory`
  existed since `f05037b`, the README described the window and the code compiled —
  but nothing in the Kotlin sources asks the platform to instantiate a factory,
  only the descriptor does, so the window could not appear in the IDE at all.
  Guarded headless by `src/test/kotlin/fennec/plugin/PluginDescriptorTest.kt`,
  which compares descriptor entries against implementing classes in both
  directions (working tree)
- Tool-window search no longer requires an embeddings service that this plugin
  never shipped: the sidecar used to fail the chunk/search RPC outright when
  `embed::embed` errored (`sidecar/src/rpc.rs`, removed `-32000` propagation),
  so with nothing serving `/v1/embeddings` the panel searched nothing. It now
  falls back to BM25 over the local index, which is why the sidecar lane grew
  from the 74 `#[test]` attributes its last hub measurement counted at `9104cfb`
  to the 80 that same command measures on the working tree today
- The results hub mis-marked this repo's Kotlin lane as blocked. `run_lane()` in
  the collector flagged any output containing the bare digits `4551` as a Smart App
  Control refusal, and cargo's own timing line — `finished in 4.551s`, which that
  run printed — carried them, so a healthy lane was recorded blocked instead of
  measured. The check now requires the real signature `os error 4551`
  (`benchmarks/results.py:219`, working tree); the lane records **46 passed**,
  carried forward from its measurement at 2026-11-23T21:26:54Z

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
