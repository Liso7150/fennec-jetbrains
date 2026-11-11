# Fennec for JetBrains

A dependency-free Fennec plugin for JetBrains Air, built around the engine idea
from [Colibri](https://github.com/JustVugg/colibri). It has no tool window of its
own: it publishes **Fennec** as an agent card in Air and installs the ACP launcher
behind it. Everything runs on this machine. Plugin version: **1.9.0**.

## What it does

- Installs the managed ACP launcher at `~/.fennec/air/fennec-acp.py`. The launcher
  owns no protocol code: it finds fennec-core on this machine and hands the process
  to `fennec_core.acp`, which speaks ACP v1 over stdio, starts the local engine on
  the first prompt and streams `session/update` chunks back to Air.
- Merges a `fennec` entry into a Fennec-owned copy of Air's agent registry at
  `~/.fennec/air/registry.json` and points Air at it with the
  `air.acp.registry.path` system property in the IDE's user `.vmoptions` file.
  Air's cached registry is the base, so every built-in agent stays visible.
- Writes the launch command (`<configured python> ~/.fennec/air/fennec-acp.py`) into
  `~/.jetbrains/acp-registry-installed-overlay.json` and marks Fennec installed in
  Air's shared `installed.json`. That is what gives Fennec a real card with
  install/enable/disable controls on Air's Agents page. Air's registry only models
  downloadable binaries, so nothing is downloaded: Fennec runs from this machine.
- Removes the old `Colibri` entry that 1.3.0 wrote into `~/.jetbrains/acp.json`
  (only when it points at the managed launcher). Other custom agents are never
  touched.
- The engine starts on the first prompt, not when Air opens. An engine the agent
  owns stops after five minutes without requests (or when the session ends), so an
  open IDE costs no model memory. An engine that was already running - started by
  the desktop app or a terminal - is attached to and never stopped.

Registry changes apply after the next IDE start, because the override is a JVM
option. The plugin deliberately provides no tab, settings page or editor actions.

## Browser / web-agent outlets (optional)

The outlets are a fennec-core feature, not plugin code: the ACP agent can hand a
solvable, URL-scoped browser task to a local outlet and read the result back. They
are optional, gated by the same per-turn approvals as every other tool, and never
required for the core coding flow. Both upstreams are used as they are - this
workspace never modifies them.

- **jev-ultrafast** (https://github.com/browser-use/jev-ultrafast): fastest
  single-model browser loop from Browser Use.
- **OpenBot** (https://github.com/CopilotKit/OpenBot): shared local workspace
  of agents with per-agent browsers and tools.

`python air/fennec-acp.py --check` prints what the agent advertises, including
whether each outlet is present.

## Build

```bash
./build-plugin.sh    # compiles against the IDE JBR, outputs build/Fennec-plugin-1.9.0.zip
```

Set `FENNEC_IDE_PLATFORM` to any IntelliJ-based IDE install (263.x) if the default
path is not on this machine. Install the zip via *Settings → Plugins → ⚙ → Install
Plugin from Disk* and restart the IDE; Fennec then appears on Air's Agents page.

## Configure

The launcher reads the suite configuration (`~/.fennec/config.json` or
`$FENNEC_CONFIG`, plus `FENNEC_*` variables) that the desktop app, CLI and
installer share: engine root, Python, model, engine URL, policy, backend and
`ctx`. No fields are duplicated into the plugin.

To run fennec-core from a non-default checkout, set `FENNEC_CORE` to the directory
that contains `fennec_core/`. The launcher otherwise uses `suite_root` (or
`FENNEC_SUITE`) and, when started from a checkout, the `fennec-core` folder next to
this repository.

Optional: add `"idle_timeout": 600` to the shared config to change how long an
idle engine stays loaded. `0` disables the idle stop.

## Tests

```bash
python -m pytest air/tests -q
```

They cover launcher discovery and flag forwarding against a fake fennec-core, in a
temporary home. No engine, no model, no network, no IDE - and no paid API. The ACP
protocol itself is tested in `fennec-core/tests`.

## Credits

Engine base idea: [JustVugg/colibri](https://github.com/JustVugg/colibri) —
device-tiered MoE inference. Fine-tuning:
[AirLLM](https://github.com/lyogavin/airllm).

Web-agent outlets (optional): [jev-ultrafast](https://github.com/browser-use/jev-ultrafast)
and [OpenBot](https://github.com/CopilotKit/OpenBot).
