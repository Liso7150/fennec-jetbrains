# Colibri for JetBrains

A dependency-free [Colibri](https://github.com/JustVugg/colibri) plugin for
JetBrains Air. It has no tool window of its own: it registers **Colibri** as
an Air agent and manages the ACP bridge behind it. Everything runs on this
machine.

## What it does

- Installs the managed bridge at `~/.colibri/air/colibri-acp.py`.
- Merges a `Colibri` entry into `~/.jetbrains/acp.json`. The first edit backs
  the file up to `acp.json.bak-colibri`; every other server, and the ACP agent
  command after `--`, is preserved.
- The engine starts on the first prompt, not when Air opens. An engine the
  bridge owns stops after five minutes without requests (or when the session
  ends), so an open IDE costs no model memory. An engine that was already
  running — started by the desktop app or a terminal — is attached to and
  never stopped.

**Where the agent appears:** Air's Agents page shows cards from JetBrains'
remote registry. Custom ACP servers like this one appear in Air's agent
selector menu, not as cards; that is an Air limitation, not a plugin setting.
The plugin deliberately provides no tab, settings page or editor actions.

## Build

```bash
./build-plugin.sh    # compiles against the IDE JBR, outputs build/ColibriCode-plugin-1.3.0.zip
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk*, then
restart Air and pick **Colibri** in the agent selector.

## Configure

The bridge reads the suite configuration (`~/.colibri/config.json` or
`$COLIBRI_CONFIG`, plus `COLIBRI_*` variables) that the desktop app, CLI and
installer share: engine root, Python, model, engine URL, policy, backend and
`ctx`. No fields are duplicated into the plugin.

Optional: add `"idle_timeout": 600` to the shared config, or put
`--idle-timeout 600` before the `--` in the `Colibri` entry of
`acp.json`, to change how long an idle engine stays loaded. `0` disables the
idle stop.

## Engine lifecycle

The engine is local and lazy. While no prompt has arrived, no engine process
exists at all. The first prompt starts `coli serve` on the configured loopback
URL; the bridge proxies OpenAI-compatible traffic (including SSE streaming) to
it. After the idle timeout the bridge stops the engine it started; the next
prompt starts a fresh one. If Air or the bridge is killed hard, the next
bridge adopts the engine left behind and stops it when idle. Every model,
cache and log file stays on this machine; the plugin never contacts a remote
service.

## ACP bridge (JetBrains Air)

The bridge lives in [`air/`](air/README.md) and can also be wired into
`acp.json` by hand. Its tests use a fake engine and a fake ACP agent.

## Credits

Engine: [JustVugg/colibri](https://github.com/JustVugg/colibri). Fine-tuning:
[AirLLM](https://github.com/lyogavin/airllm).
