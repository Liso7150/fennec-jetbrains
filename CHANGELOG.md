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

## [1.0.0] - 2026-11-26

This release replaces the 2026-11-24 build. Measured on the working tree: **91 Rust tests
passed** (75 unit, 16 integration), **50 Kotlin checks passed**, and live IntelliJ smoke tests
covered Access, ready-model selection, High reasoning persistence, and the recommended-model
download/selection flow.

### Added
- Access, Model, and Reasoning buttons on Air's `Fennec [*]` composer line, styled with the
  Fennec Desktop muted-chip, mode-color, border, and arrow vocabulary
- Auto, Low, Medium, and High reasoning levels in the model menu, persisted as
  `reasoning_effort` in the shared config and preserved by access/model changes
- “Download new model…” in the model menu: a confirmed, cancellable background download of
  `Liso/qwen36-35b-colibri` through `fennec-core/setup_wizard.py`, with resumable partial files,
  progress, environment-only `HF_TOKEN` use, and automatic selection when complete

### Changed
- Composer controls live on the same line as Air's Fennec agent label instead of a separate
  strip or the removed side tool window
- Model changes preserve access and reasoning; access changes preserve model and reasoning

### Fixed
- Air's action groups registered Fennec actions but did not render them in this build; the
  controls now install directly into the composer container
- Composer dropdowns size to their content and remain usable in the live New Session window
- The sidecar exposes its Python interpreter and suite root so the plugin can reuse core's
  downloader without shelling out through a command interpreter

## [1.0.0] - 2026-11-24 (superseded)
This build was replaced by the 2026-11-26 release.
Test counts come from the results hub
(`benchmarks/artifacts/hub/RESULTS.json`, updated 2026-11-25T20:16:55Z): the
`jetbrains-rust` lane reports **90 passed / 0 failed** for `cargo test --offline`
in `sidecar/`, and the `jetbrains-kotlin` lane **50 passed / 0 failed** for
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
- Native composer controls with Ask every time, Auto approve, and Full access,
  plus engine-ready local model selection. Access changes affect new ACP permission
  requests immediately; model changes update the shared config atomically

### Changed
- The structural file-index/search panel and its editor/log panes are removed; Fennec's
  Access and Model selectors now live directly in Air's New Session composer
- The settings panel drops the obsolete search-result limit and keeps the sidecar
  path, engine Python, update check, and channel controls
- The packaged plugin directory and jar are named `fennec-jetbrains`, matching
  the repository (they were inherited as `fennec-desktop`)
- `build-plugin.sh` resolves Kotlin from PATH or `FENNEC_KOTLINC`, detects the IDE
  and Rust host target instead of hardcoding this machine, and packages only the
  configured sidecar target

### Fixed
- Auto approve and Full access now answer ACP permission requests in the sidecar
  instead of leaving the control cosmetic; Ask every time still forwards every request
- Model changes preserve unrelated shared-config keys, reject malformed JSON, validate
  the selected folder, write atomically, and leave a backup
- The composer model selector now uses core's readiness classifier, lists only servable
  checkpoints, normalizes Windows path separators, and refuses unconverted models such as
  `edge0-35b-a3b` instead of letting the engine fail at prompt time
- The 1.0.0 release was replaced on 2026-11-25 with this fixed plugin after the old
  release asset and tag were backed up and removed
- The version compare is numeric per component, so `1.10.0` no longer ranks
  below `1.9.0`, and a tag that only differs by a pre-release suffix is not an update
- The check reports the release page instead of promising "restart your IDE to
  apply the update": a plugin is replaced by the IDE's own installer, never by itself
- The experimental channel reads `main`, which is this repository's trunk
- `"Fennec Updates"` is registered as a notification group, so a found update can
  actually be shown
- Composer action registration is guarded headlessly by `PluginDescriptorTest`, which
  verifies both Fennec actions target Air's composer group and every registered class exists
- Tool-window search no longer requires an embeddings service that this plugin
  never shipped: the sidecar used to fail the chunk/search RPC outright when
  `embed::embed` errored (`sidecar/src/rpc.rs`, removed `-32000` propagation),
  so with nothing serving `/v1/embeddings` the panel searched nothing. It now
  falls back to BM25 over the local index, which is why the sidecar lane grew
  from the 74 `#[test]` attributes its last hub measurement counted at `9104cfb`
  to the 90 that same command measures on the working tree today
- The results hub mis-marked this repo's Kotlin lane as blocked. `run_lane()` in
  the collector flagged any output containing the bare digits `4551` as a Smart App
  Control refusal, and cargo's own timing line — `finished in 4.551s`, which that
  run printed — carried them, so a healthy lane was recorded blocked instead of
  measured. The check now requires the real signature `os error 4551`
  (`benchmarks/results.py:219`, working tree); the lane records **50 passed**,
  measured at 2026-11-25T20:16:55Z

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
