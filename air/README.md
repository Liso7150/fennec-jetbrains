# Colibri ACP bridge for JetBrains Air

JetBrains Air talks to agents over the Agent Client Protocol (stdio). This
stdlib-only bridge makes Colibri work there while keeping model memory off
until it is actually needed:

1. starts the real ACP agent (Qwen Code) with `OPENAI_BASE_URL`,
   `OPENAI_API_KEY` and `OPENAI_MODEL` pointing at a loopback proxy it owns;
2. starts `coli serve` behind that proxy **on the first proxied request**, not
   when Air launches the agent;
3. proxies OpenAI-compatible traffic in both directions, including chunked SSE
   streaming, so answers stream while they are generated;
4. stops an engine it owns after `--idle-timeout` seconds without requests
   (default 300, `0` disables), and again when the agent exits;
5. never stops an engine it did not start. If a bridge is killed hard, the
   lease file it leaves lets the next bridge adopt the engine and stop it when
   idle.

Everything stays on the machine. No model is downloaded and no request leaves
the loopback interface.

## Where it comes from

The Colibri IDE plugin installs this file as `~/.colibri/air/colibri-acp.py`
and writes the `Colibri` entry in `~/.jetbrains/acp.json` for you. Running the
bridge from this checkout by hand also works; see below.

## Configure JetBrains Air by hand

`~/.jetbrains/acp.json`, using the Python you want to run this bridge with;
absolute paths are required:

```json
{
  "agent_servers": {
    "Colibri": {
      "command": "C:/Users/you/Colibri/.venv/Scripts/python.exe",
      "args": [
        "C:/Users/you/.colibri/air/colibri-acp.py",
        "--",
        "C:/Users/you/AppData/Local/qwen-code/qwen-code/node/node.exe",
        "C:/Users/you/AppData/Local/qwen-code/qwen-code/lib/cli-entry.js",
        "--acp"
      ]
    }
  }
}
```

Restart Air after saving. Colibri appears in Air's agent menu. (Air's Agents
page cards come from JetBrains' remote registry; custom ACP servers appear in
the agent selector, not as cards.)

## Resolution order

| Setting | Flags | Environment | Shared config |
|---|---|---|---|
| engine URL | `--engine-url` | `COLIBRI_ENGINE_URL` | `engine_url` |
| model dir | `--model` | `COLIBRI_MODEL` | `model` |
| Python | `--python` | `COLIBRI_PYTHON` | `python` |
| engine root | — | `COLIBRI_HOME` | `engine_root` |
| launcher | `--launcher` | — | `<engine_root>/c/coli` |
| policy / ctx | `--policy`, `--ctx` | — | `policy`, `ctx` |
| backend | `--backend` | `COLIBRI_BACKEND` | `backend` |
| idle stop | `--idle-timeout` | `COLIBRI_ACP_IDLE_TIMEOUT` | `idle_timeout` |
| API key | — | `COLIBRI_API_KEY` / `COLI_API_KEY` | never stored (local default) |
| ACP model id | `--model-id` | `OPENAI_MODEL` | `model_id`, else derived |

The shared config is `~/.colibri/config.json` (or `COLIBRI_CONFIG`). Only a
loopback `http` origin is accepted for auto-start; remote servers must be
configured directly in the ACP environment.

The engine validates the `model` field of every request against the id it was
started with. The bridge derives that id from the engine's own
`family_registry` before the engine exists; if an attached engine reports a
different id, the bridge rewrites the request's `model` field to match instead
of failing the prompt.

### GPU backend

`coli serve` has no `--backend` flag: the engine picks its backend from
`COLI_VULKAN`, `COLI_CUDA` and `COLI_METAL` (`=1` on, `=0` off). When the
resolved backend is not `auto`, the bridge sets all three variables explicitly
(the chosen one to `1`, the rest to `0`) before spawning the engine, so a
configured GPU is actually used. With `auto` the engine keeps its own default
(CPU+SSD), which is why the log line `[VK] … COLI_VULKAN is not set` appears.
The desktop app and this bridge share the same `backend` setting.

## Check it

```bash
python colibri-acp.py --check
```

prints the engine endpoint, model ids and idle timeout, or exits 1 when
nothing is listening. `--check` never starts an engine. The engine log for
on-demand starts is `<TEMP>/colibri-acp-engine.log`; leases live next to the
managed bridge in `~/.colibri/air/`.

## Tests

```bash
python -m unittest discover -s air/tests -v
```

The tests use a fake engine and a fake ACP agent: they verify that the engine
stays down until the first chat request, that listing models never starts it,
idle stop and restart, attached engines staying warm, adoption after a hard
kill, backend environment injection, error codes, and SSE passthrough.
