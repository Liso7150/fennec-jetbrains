# Colibri for JetBrains

A dependency-free [Colibri](https://github.com/JustVugg/colibri) plugin for
JetBrains IDEs: a right-side SSE chat tool window plus editor actions
**Explain / Fix / Write Tests** (Ctrl+Alt+E / F). Talks to the engine's
OpenAI-compatible server and starts it locally on demand.

## Features

- SSE chat tool window, one per project; Stop cancels only that project's stream.
- Explain / Fix / Write Tests editor actions.
- Engine starts on the first message, attaches to a running engine, and stops the engine it owns when the IDE closes.
- Shared suite config with per-project overrides; local bearer key by default.
- ACP bridge for JetBrains Air (`air/colibri-acp.py`) with its own test suite.

## Build

```bash
./build-plugin.sh    # compiles against the IDE JBR, outputs build/ColibriCode-plugin-1.1.0.zip
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk*.

## Configure

*Settings → Tools → Colibri Code*. Every field is optional: blank fields are
inherited from the suite configuration (`~/.colibri/config.json` or
`$COLIBRI_CONFIG`, plus `COLIBRI_*` variables) that the desktop app, CLI and
installer share. Fresh installs therefore need no setup when the suite is
installed. Engine URL defaults to `http://127.0.0.1:8184`; the API key comes
from `COLIBRI_API_KEY` (local bearer by default).

Each project gets its own tool window; Stop cancels the in-flight stream in
that project only.

## Engine lifecycle

The engine is local and on demand. The first message in a tool window starts
`coli serve` on the configured loopback URL when nothing is listening; the
status line follows model loading, and the Start engine button does the same
by hand. If the plugin spawned that process, it is stopped (with children)
when the IDE closes. An engine that was already running — started by the
desktop app or your own shell — is attached to and left running. Every
model, cache and log file stays on this machine; the plugin never contacts a
remote service.

## ACP bridge (JetBrains Air)

Colibri runs inside JetBrains Air as an ACP agent through the on-demand bridge
in [`air/`](air/README.md). The bridge starts the local engine when the first
prompt arrives, launches Qwen Code (or any ACP agent) with `OPENAI_*` pointed at
it, and stops the engine it started when the session ends. Configure
`~/.jetbrains/acp.json` once with your absolute paths; the bridge reads the
shared `~/.colibri/config.json` for the engine URL and model.

## Credits

Engine: [JustVugg/colibri](https://github.com/JustVugg/colibri). Fine-tuning:
[AirLLM](https://github.com/lyogavin/airllm).