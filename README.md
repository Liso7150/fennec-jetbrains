![Fennec for JetBrains 1.0.0 — a local coding agent inside your IDE: Kotlin plugin, bundled Rust sidecar, no cloud. Registers Fennec as an agent card in JetBrains Air. IntelliJ Platform 263.x, Kotlin 2.4.21, Rust sidecar, 73 Rust tests, 34 Kotlin checks, MIT license.](docs/hero.png)

Registers **Fennec** as an agent card in JetBrains Air, installs the native ACP launcher
behind it, and adds a tool window for structural code search. Everything runs on this machine.

## At a glance

| Rust unit tests | Rust integration tests | Kotlin checks | Plugin zip | Sidecar binary |
|---|---|---|---|---|
| 60 | 13 | 34 | 381 KiB | 598 KiB |

Sources: counted in `sidecar/src/`, `sidecar/tests/cli.rs`, `src/test/kotlin/`; sizes from
`build/Fennec-plugin-2.5.0.zip` (389,735 B) and the built `fennec-acp.exe` (612,352 B).

## Architecture

```
JetBrains Air ── ACP v1 over stdio ──► fennec-acp (Rust supervisor)
                                          │ spawns, pumps stdio, propagates exit codes
                                          ▼
                                     fennec_core.acp (Python)
                                          │ engine starts on first prompt
                                          ▼
                                     local engine (default HTTP :8184)

Fennec tool window ── NDJSON JSON-RPC (--rpc) ──► fennec-acp
                                                   ├─ context/chunk   (pure Rust)
                                                   └─ context/search  (cosine index)
                                                         └─ embeddings :8182
```

| Layer | Owns |
|---|---|
| Kotlin | Air registration, Kotlin UI DSL settings panel, tool window; PSI in `readAction`, Swing on the EDT, sidecar streaming bridged with coroutine `Flow` |
| Rust (`sidecar/`, self-contained crate) | the single `fennec-acp` binary: ACP launcher/supervisor **and** the `--rpc` context service (`context/chunk`, `context/search`) |
| fennec-core | ACP v1 itself (`fennec_core/acp.py`); the sidecar owns no protocol code |

> [!NOTE]
> The sidecar is a **subprocess**, not a JNI/Panama library, and the protocol is JSON-RPC
> over stdio — not MCP. A crash in the native code or the agent cannot take down the IDE.
> The sidecar's only dependencies are `serde`, `serde_json` and `httparse`.

## What it does

| Behavior | Detail |
|---|---|
| Sidecar install | `~/.fennec/air/fennec-acp[.exe]`, registered as the launch command in `~/.jetbrains/acp-registry-installed-overlay.json` (the binary is the command; no arguments) |
| Registry merge | a `fennec` entry in a Fennec-owned copy of Air's registry at `~/.fennec/air/registry.json`; Air is pointed at it via `air.acp.registry.path` in the user `.vmoptions`. Air's cached registry is the base, so built-in agents stay visible |
| Installed card | marks Fennec in Air's shared `installed.json` — a real card with install/enable/disable controls; nothing is downloaded |
| Legacy cleanup | removes the old `Colibri` entry from `~/.jetbrains/acp.json` only when it points at a managed launcher (sidecar or retired Python shim); other custom agents are never touched |
| Engine lifecycle | starts on the first prompt, stops after idle; an already-running engine is attached to and left alone |

Registry changes apply after the next IDE start (the override is a JVM option). On an OS
with no bundled sidecar binary, registration is skipped with a warning.

## Tool window and settings

The **Fennec** tool window (right anchor) indexes the open file into structural chunks
(declarations and blocks with byte ranges and symbol names), embeds them, and runs semantic
search; clicking a result navigates to the symbol. Settings under **Tools → Fennec**: an
optional sidecar path override, the search result limit, and a read-only view of the engine
Python resolved from the shared config.

Semantic search uses the suite's loopback embedding service at
`http://127.0.0.1:8182/v1/embeddings` (override with `FENNEC_EMBED_URL`). If the service is
unreachable the sidecar returns a JSON-RPC error — it never fabricates embeddings or scores.

## Browser / web-agent outlets (optional)

A fennec-core feature, not plugin code: the ACP agent can hand a solvable, URL-scoped browser
task to a local outlet and read the result back. Optional, gated by the same per-turn
approvals as every other tool, never required for the core coding flow. Both upstreams are
used as they are: [jev-ultrafast](https://github.com/browser-use/jev-ultrafast) and
[OpenBot](https://github.com/CopilotKit/OpenBot).

`fennec-acp --check` forwards the flag to `fennec_core.acp`, which prints what the agent
advertises — including whether each outlet is present.

## Build

```bash
./build-plugin.sh    # outputs build/Fennec-plugin-1.0.0.zip
```

Builds the Rust sidecar offline, compiles Kotlin against the IDE platform with the local
`kotlinc` (no Gradle, `-jvm-target 21`), and packages every binary it finds under
`sidecar/<os-dir>/`. The IDE provides the Kotlin stdlib and coroutines; neither is bundled.

| Variable | Default | Meaning |
|---|---|---|
| `FENNEC_IDE_PLATFORM` | a RustRover 2026.3 install | any IntelliJ-based IDE install (263.x) to compile against |
| `KOTLINC` | the suite's Kotlin 2.4.21 compiler | path to `kotlinc` / `kotlinc.bat` |
| `FENNEC_SIDECAR_TARGET` | `x86_64-pc-windows-gnu` | Rust target triple for the sidecar |

Install the zip via *Settings → Plugins → Install Plugin from Disk* and restart the IDE;
Fennec then appears on Air's Agents page.

### Platform support

> [!IMPORTANT]
> Only the **Windows x64** sidecar is built in this repository. The macOS and Linux binaries
> are **not built** here — cross-compiling them needs toolchains and linkers that are not
> present on this machine. No stub or placeholder binaries are shipped. On those platforms
> the plugin loads, but Air registration and the tool window report that no sidecar is
> bundled until a binary is built and packaged.

To produce them on a machine with the right toolchains:

```bash
rustup target add x86_64-apple-darwin aarch64-apple-darwin \
                  x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
FENNEC_SIDECAR_TARGET=x86_64-apple-darwin        ./build-plugin.sh   # macOS x64
FENNEC_SIDECAR_TARGET=aarch64-apple-darwin       ./build-plugin.sh   # macOS ARM
FENNEC_SIDECAR_TARGET=x86_64-unknown-linux-gnu   ./build-plugin.sh   # Linux x64
FENNEC_SIDECAR_TARGET=aarch64-unknown-linux-gnu  ./build-plugin.sh   # Linux ARM
```

## Configure

The sidecar reads the suite configuration (`~/.fennec/config.json` or `$FENNEC_CONFIG`, plus
`FENNEC_*` variables) shared with the desktop app, CLI and installer: engine root, Python,
model, engine URL, policy, backend and `ctx`. No fields are duplicated into the plugin.

| Override | Effect |
|---|---|
| `FENNEC_CORE` | run fennec-core from a non-default checkout (the directory containing `fennec_core/`) |
| `FENNEC_SUITE` / `suite_root` | where to look for the `fennec-core` folder; a checkout next to this repo is also probed |
| `FENNEC_ACP_IDLE_TIMEOUT` | idle-engine stop timeout in seconds, read by fennec-core (default 300) |

## Tests

```bash
./run-tests.sh
```

| Suite | Count | Covers |
|---|---|---|
| Rust unit (`sidecar/`) | 60 | discovery search order, chunking, cosine index, embedding client, JSON-RPC dispatch |
| Rust integration (`sidecar/tests/cli.rs`) | 13 | the built binary over stdio: flag forwarding, crash/exit-code propagation, EOF shutdown, chunk/search against a loopback fake embedding service |
| Kotlin headless | 34 | SharedConfig contract and Air registration: registry merge, overlay, vmoptions, idempotency, legacy cleanup; injected environments and temporary homes |

No engine, no model, no real network, no IDE — and no paid API. The ACP protocol itself is
tested in `fennec-core/tests`. The Kotlin UI compiles against the real platform but is not
exercised by these tests.

## Credits

Engine: [Liso7150/fennec](https://github.com/Liso7150/fennec). Base idea:
[JustVugg/colibri](https://github.com/JustVugg/colibri) — device-tiered MoE inference.
Fine-tuning: [AirLLM](https://github.com/lyogavin/airllm). Outlets:
[jev-ultrafast](https://github.com/browser-use/jev-ultrafast),
[OpenBot](https://github.com/CopilotKit/OpenBot). Part of the
[Fennec suite](https://github.com/Liso7150/fennec-environment-setup).