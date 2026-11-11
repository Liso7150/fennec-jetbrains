# Fennec ACP launcher for JetBrains Air

JetBrains Air talks to agents over the Agent Client Protocol (ACP, stdio JSON-RPC).
This file is the entry point Air starts; **the protocol lives in fennec-core**, so
the IDE, the desktop app, the CLI and the browser agents all share one ACP
implementation instead of four dialects.

The launcher does three things:

1. Finds the `fennec_core` package on this machine (table below).
2. Puts that checkout on `sys.path` and imports `fennec_core.acp`.
3. Calls its `main()`, which speaks ACP v1 over stdio: `initialize`,
   `session/new`, `session/load`, `session/resume`, `session/list`,
   `session/close`, `session/set_mode`, `session/prompt` with streamed
   `session/update` chunks, `session/cancel` notifications, and
   `session/request_permission` before any gated tool call.

Behaviour that Air sees - lazy engine start on the first prompt, streamed
`agent_message_chunk` / `agent_thought_chunk` updates, an owned engine stopped
after the idle timeout, an attached engine left running - is implemented and
tested in [`fennec-core/fennec_core/acp.py`](../../fennec-core/fennec_core/acp.py).
Stdlib only here; nothing is downloaded and no request leaves the loopback
interface.

## Finding fennec-core

| Order | Source | Value |
|---|---|---|
| 1 | `FENNEC_CORE` | a directory holding `fennec_core/acp.py` |
| 2 | `FENNEC_SUITE`, then `suite_root` in `~/.fennec/config.json` | `<suite>/fennec-core` |
| 3 | next to a checkout | `<launcher>/../../fennec-core` |
| 4 | installed package | `import fennec_core` from the interpreter's environment |

A candidate only counts when it carries `fennec_core/acp.py`: a fennec-core from
before the ACP agent has the package and not the module, and importing it is a
traceback Air cannot show. The first candidate that qualifies wins, so an old
sibling checkout never shadows a usable one.

The interpreter itself comes from the shared config (`python`, or
`FENNEC_PYTHON`), resolved the way fennec-core resolves it - a bare name is
searched on `PATH`, a path is made absolute - and is chosen by the plugin when it
writes Air's launch overlay, because Air does not inherit the terminal's `PATH`.
If nothing is found, the launcher exits `2` and names the directories it searched.

## Flags

| Flag | Effect |
|---|---|
| `--check` | print the capabilities the agent advertises, then exit; never starts an engine |
| `--version` | print `fennec-acp 1.9.0` |

Anything else is rejected before the import: ACP tuning knobs belong to the
shared config, not to a second CLI.

```bash
python fennec-acp.py --check
```

```json
{
  "protocolVersion": 1, "version": "1.9.0", "agent": "fennec", "transport": "stdio",
  "methods": ["$/cancel_request", "exit", "initialize", "session/cancel", "session/close",
              "session/list", "session/load", "session/new", "session/prompt",
              "session/resume", "session/set_mode"],
  "modes": ["agent", "ask"], "engineRunning": false,
  "outlets": {"jev": {"present": false}, "openbot": {"present": false}}
}
```

Only methods the agent actually answers are listed - an advertised method Air
cannot use is worse than an absent one.

## JetBrains Air integration

The Fennec IDE plugin installs this file as `~/.fennec/air/fennec-acp.py` and
publishes Fennec through Air's agent registry (plugin v1.9.0). The registry entry
carries no binary distribution: Air treats a local command as transient, so the
plugin writes the launch command into
`~/.jetbrains/acp-registry-installed-overlay.json` and records the agent as
installed in Air's shared `installed.json` - **only when a catalog entry was
actually published**. Air renders those records through the catalog, so without
one they would be an agent the user can see, cannot use and cannot remove; when
publication is skipped (no cached Air registry yet) the plugin takes its own
records back out and leaves other agents in those files alone.

## Browser / web-agent outlets (optional)

Fennec can hand a solvable, URL-scoped browser task to a local web agent and read
the result back. It is a fennec-core outlet, gated by the same approval as every
other tool, and never required for the coding flow. The targets are local, and
their credentials and traces stay with them.

- **jev-ultrafast** (https://github.com/browser-use/jev-ultrafast): a fast,
  single-model browser loop that picks an operation and a compatible element
  index from an indexed page snapshot. Give the **end state**, not the hops.
- **OpenBot** (https://github.com/CopilotKit/OpenBot): a shared local workspace of
  agents, each with its own browser, files, role, conversation and queue.

Neither upstream is modified or vendored; Fennec only drives what is already
installed and speaks to it over loopback.

## Model

A local model folder must contain `config.json` and weight files
(`.safetensors` / `.gguf`). Run `fennec setup`, set `"model"` in
`~/.fennec/config.json`, or place a model under the configured `models_root`
(`~/fennec-models/` by default).

## Tests

```bash
python -m pytest air/tests -q
```

Discovery, precedence, flag forwarding and the missing-core message are covered
against a fake fennec-core in a temporary home. The protocol itself is tested in
`fennec-core/tests/test_acp.py`.
