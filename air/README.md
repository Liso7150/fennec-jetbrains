# Colibri ACP bridge for JetBrains Air

JetBrains Air talks to agents over the Agent Client Protocol (stdio). This small
stdlib-only shim makes Colibri work there without a permanently running server:

1. probes `http://127.0.0.1:8184/v1/models` (or `engine_url` from the shared config);
2. if nothing answers, starts `coli serve` locally with your configured model;
3. launches the real ACP agent (Qwen Code) with `OPENAI_BASE_URL`,
   `OPENAI_API_KEY` and `OPENAI_MODEL` pointing at that local engine;
4. when the agent exits, stops the engine **only if this bridge started it**.
   An engine that was already running (desktop app, terminal) is attached to and
   left alone.

Everything stays on the machine. No model is downloaded and no request leaves
the loopback interface.

## Configure JetBrains Air

Edit `~/.jetbrains/acp.json` and add (or replace) the `Colibri` server. Use the
Python you want to run this shim with; absolute paths are required.

```json
{
  "agent_servers": {
    "Colibri": {
      "command": "C:/Users/you/Colibri/engine/.venv/Scripts/python.exe",
      "args": [
        "C:/Users/you/Colibri-Suite/colibri-jetbrains/air/colibri-acp.py",
        "--",
        "C:/Users/you/AppData/Local/qwen-code/qwen-code/node/node.exe",
        "C:/Users/you/AppData/Local/qwen-code/qwen-code/lib/cli-entry.js",
        "--acp"
      ],
      "env": {
        "QWEN_CODE_LAUNCHER_PATH": "C:/Users/you/AppData/Local/qwen-code/qwen-code/bin/qwen.cmd"
      },
      "use_idea_mcp": true,
      "use_custom_mcp": true,
      "idea_mcp_allowed_tools": []
    }
  }
}
```

Restart Air after saving. The Colibri agent appears like any other ACP server;
the engine starts on the first prompt and goes away when Air closes the session.

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
| API key | — | `COLIBRI_API_KEY` / `COLI_API_KEY` | never stored (local default) |
| ACP model id | `--model-id` | `OPENAI_MODEL` | first id from `/v1/models` |

The shared config is `~/.colibri/config.json` (or `COLIBRI_CONFIG`). Only a
loopback `http` origin is accepted for auto-start; remote servers must be
configured directly in the ACP environment.

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

prints the engine endpoint and model ids, or exits 1 when nothing is listening.
The engine log for on-demand starts is `<TEMP>/colibri-acp-engine.log`.

## Tests

```bash
python -m unittest discover -s air/tests -v
```

The tests use a fake engine and a fake ACP agent: they verify spawn-on-demand,
environment injection, exit-code propagation, and that an attached engine is
never stopped.
