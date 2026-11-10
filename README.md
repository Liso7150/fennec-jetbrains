# Colibri for JetBrains

A dependency-free [Colibri](https://github.com/JustVugg/colibri) plugin for
JetBrains IDEs: a right-side SSE chat tool window plus editor actions
**Explain / Fix / Write Tests** (Ctrl+Alt+E / F). Talks to the engine's
OpenAI-compatible server; optional engine auto-start.

## Build

```bash
./build-plugin.sh    # compiles against the IDE JBR, outputs build/ColibriCode-plugin-1.0.0.zip
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

## ACP bridge (JetBrains Air)

Colibri also runs inside JetBrains Air as an ACP agent via Qwen Code: point
`~/.jetbrains/acp.json` at the Qwen Code CLI with `OPENAI_BASE_URL` set to the
Colibri server and `OPENAI_MODEL` to your model id. See the
[environment setup](https://github.com/Liso7150/colibri-environment-setup).

## Credits

Engine: [JustVugg/colibri](https://github.com/JustVugg/colibri). Fine-tuning:
[AirLLM](https://github.com/lyogavin/airllm).