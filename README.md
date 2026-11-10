# Colibri for JetBrains

A dependency-free [Colibri](https://github.com/JustVugg/colibri) plugin for
JetBrains Air. It has no tool window of its own: it publishes **Colibri Code**
as an agent card in Air and manages the ACP bridge behind it. Everything runs
on this machine.

## What it does

- Installs the managed bridge at `~/.colibri/air/colibri-acp.py`.
- Merges a `colibri` entry into a Colibri-owned copy of Air's agent registry at
  `~/.colibri/air/registry.json` and points Air at it with the
  `air.acp.registry.path` system property in the IDE's user `.vmoptions` file.
  Air's cached registry is the base, so every built-in agent stays visible.
- Registers the bridge's launch command in `~/.jetbrains/acp-registry-installed-overlay.json`
  and marks Colibri installed in Air's shared `installed.json`. That is what
  gives Colibri a real card with install/enable/disable controls on Air's
  Agents page.
- Removes the old `Colibri` entry that 1.3.0 wrote into `~/.jetbrains/acp.json`
  (only when it points at the managed bridge). Other custom agents are never
  touched.
- The engine starts on the first prompt, not when Air opens. An engine the
  bridge owns stops after five minutes without requests (or when the session
  ends), so an open IDE costs no model memory. An engine that was already
  running — started by the desktop app or a terminal — is attached to and
  never stopped.

Registry changes apply after the next IDE start, because the override is a JVM
option. The plugin deliberately provides no tab, settings page or editor
actions.

## Build

```bash
./build-plugin.sh    # compiles against the IDE JBR, outputs build/ColibriCode-plugin-1.3.2.zip
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk* and
restart the IDE. Colibri Code then appears on Air's Agents page.

## Configure

The bridge reads the suite configuration (`~/.colibri/config.json` or
`$COLIBRI_CONFIG`, plus `COLIBRI_*` variables) that the desktop app, CLI and
installer share: engine root, Python, model, engine URL, policy, backend and
`ctx`. No fields are duplicated into the plugin.

Optional: add `"idle_timeout": 600` to the shared config to change how long an
idle engine stays loaded. `0` disables the idle stop.

## Engine lifecycle

The engine is local and lazy. While no prompt has arrived, no engine process
exists at all. The bridge itself listens on the configured loopback URL (the
one Qwen Code and Air are pointed at), answers `/v1/models` from memory, and on
the first prompt starts `coli serve` on a separate loopback port of its own
choosing, proxying OpenAI-compatible traffic (including SSE streaming) to it.
Streaming chat responses get their SSE headers immediately and periodic empty
chunks while the engine prefills, so a slow first token does not trip client
timeouts. After the idle timeout the bridge stops the engine it started; the
next prompt starts a fresh one. If Air or the bridge is killed hard, the next
bridge adopts the engine left behind and stops it when idle. Every model,
cache and log file stays on this machine; the plugin never contacts a remote
service.

## ACP bridge (JetBrains Air)

The bridge lives in [`air/`](air/README.md) and can also be wired into
`acp.json` by hand. Its tests use a fake engine and a fake ACP agent.

## Credits

Engine: [JustVugg/colibri](https://github.com/JustVugg/colibri). Fine-tuning:
[AirLLM](https://github.com/lyogavin/airllm).
