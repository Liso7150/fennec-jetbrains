#!/usr/bin/env python3
"""On-demand Colibri ACP bridge for JetBrains Air.

Air speaks the Agent Client Protocol over stdio. This shim starts the local
Colibri engine only when the configured loopback endpoint is not already
serving, runs the ACP agent (Qwen Code by default) with OPENAI_* pointed at it,
and stops the engine it spawned when the agent exits. An engine that was
already running on the machine is attached to and left untouched.

Usage in ~/.jetbrains/acp.json (paths adjusted for your install):

  "Colibri": {
    "command": "C:/Users/you/Colibri/engine/.venv/Scripts/python.exe",
    "args": [
      "C:/Users/you/Colibri-Suite/colibri-jetbrains/air/colibri-acp.py",
      "--",
      "C:/Users/you/AppData/Local/qwen-code/qwen-code/node/node.exe",
      "C:/Users/you/AppData/Local/qwen-code/qwen-code/lib/cli-entry.js",
      "--acp"
    ]
  }

Everything after -- is the ACP agent command. Stdlib only; all paths are local.
"""
from __future__ import annotations

import argparse
import http.client
import ipaddress
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

START_TIMEOUT_S = int(os.environ.get("COLIBRI_ACP_TIMEOUT", "900"))
POLL_S = 2.0


def log(message):
    print(f"[colibri-acp] {message}", file=sys.stderr, flush=True)


def is_loopback(host):
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def shared_config():
    path = Path(os.environ.get("COLIBRI_CONFIG") or Path.home() / ".colibri" / "config.json")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as exc:
        raise SystemExit(f"cannot read {path}: {exc}")


def engine_settings(args, cfg):
    raw_url = (args.engine_url or os.environ.get("COLIBRI_ENGINE_URL")
               or cfg.get("engine_url") or "http://127.0.0.1:8184")
    url = urlsplit(raw_url)
    if url.scheme != "http" or not url.hostname or url.port == 0:
        raise SystemExit("engine URL must be a local http endpoint, e.g. http://127.0.0.1:8184")
    if url.path not in ("", "/") or url.query or url.username or url.password:
        raise SystemExit("engine URL must be an origin without credentials, path or query")
    host = url.hostname
    if not is_loopback(host):
        raise SystemExit("the ACP bridge only starts a local engine; set OPENAI_BASE_URL yourself for remote servers")
    port = url.port or 80
    engine_root = Path(os.environ.get("COLIBRI_HOME") or cfg.get("engine_root") or Path.home() / "Colibri")
    windows = os.name == "nt"
    python = (args.python or os.environ.get("COLIBRI_PYTHON") or cfg.get("python")
              or engine_root / ".venv" / ("Scripts/python.exe" if windows else "bin/python"))
    settings = {
        "origin": f"http://{host}:{port}",
        "host": host,
        "port": port,
        "model": args.model or os.environ.get("COLIBRI_MODEL") or cfg.get("model") or "",
        "python": str(python),
        "launcher": str(args.launcher or engine_root / "c" / "coli"),
        "policy": args.policy or cfg.get("policy") or "balanced",
        "ctx": args.ctx if args.ctx is not None else int(cfg.get("ctx") or 0),
        "api_key": os.environ.get("COLIBRI_API_KEY") or os.environ.get("COLI_API_KEY") or "local",
    }
    settings["model_id"] = args.model_id or os.environ.get("OPENAI_MODEL") or ""
    return settings


def engine_models(settings):
    """Return the engine's model ids, or None when nothing healthy answers."""
    request = urllib.request.Request(settings["origin"] + "/v1/models")
    if settings["api_key"]:
        request.add_header("Authorization", "Bearer " + settings["api_key"])
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            if response.status != 200:
                return None
            payload = json.loads(response.read(1_000_000))
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, ValueError, OSError):
        return None
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, list):
        return None
    return [str(item.get("id")) for item in data if isinstance(item, dict) and item.get("id")]


def start_engine(settings):
    if not settings["model"] or not Path(settings["model"]).is_dir():
        raise SystemExit("no local model configured; pass --model /path/to/model or set model in ~/.colibri/config.json")
    if not Path(settings["python"]).is_file():
        raise SystemExit(f"Python runtime not found: {settings['python']}")
    if not Path(settings["launcher"]).is_file():
        raise SystemExit(f"Colibri launcher not found: {settings['launcher']}")
    cmd = [settings["python"], settings["launcher"], "serve",
           "--model", settings["model"], "--auto-tier",
           "--policy", settings["policy"], "--ctx", str(settings["ctx"]),
           "--host", settings["host"], "--port", str(settings["port"]),
           "--api-key", settings["api_key"]]
    log_file = Path(os.environ.get("TEMP") or os.environ.get("TMPDIR") or "/tmp") / "colibri-acp-engine.log"
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    log(f"starting local engine on {settings['origin']} (log: {log_file})")
    with open(log_file, "ab") as stream:
        return subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=stream, stderr=stream,
                                env=env, start_new_session=os.name != "nt")


def wait_until_ready(settings, engine):
    deadline = time.monotonic() + START_TIMEOUT_S
    tick = 0
    while time.monotonic() < deadline:
        models = engine_models(settings)
        if models is not None:
            log("engine ready")
            return models
        if engine.poll() is not None:
            raise SystemExit(f"the engine exited during startup; see {Path(os.environ.get('TEMP') or '/tmp') / 'colibri-acp-engine.log'}")
        tick += 1
        if tick % 5 == 0:
            log(f"loading model... ({tick * int(POLL_S)}s)")
        time.sleep(POLL_S)
    raise SystemExit("engine did not become ready in time")


def stop_owned(engine):
    if engine is None or engine.poll() is not None:
        return
    log("stopping the engine started for this session")
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(engine.pid), "/T", "/F"],
                       capture_output=True, check=False)
        return
    try:
        os.killpg(engine.pid, signal.SIGTERM)
    except OSError:
        engine.terminate()
    try:
        engine.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(engine.pid, signal.SIGKILL)
        except OSError:
            engine.kill()


def parser():
    ap = argparse.ArgumentParser(description="Run an ACP agent against an on-demand local Colibri engine")
    ap.add_argument("--engine-url")
    ap.add_argument("--model", help="model directory to serve when the endpoint is silent")
    ap.add_argument("--model-id", help="OPENAI_MODEL override; defaults to the engine's first model")
    ap.add_argument("--python", help="Python runtime for the engine (defaults to <engine_root>/.venv)")
    ap.add_argument("--launcher", help="engine launcher path (defaults to <engine_root>/c/coli)")
    ap.add_argument("--policy")
    ap.add_argument("--ctx", type=int)
    ap.add_argument("--check", action="store_true", help="report engine status without starting it or any agent")
    ap.add_argument("acp", nargs=argparse.REMAINDER, help="ACP agent command after --")
    return ap


def strip_separator(argv):
    return argv[1:] if argv and argv[0] == "--" else argv


def main(argv=None):
    args = parser().parse_args(argv)
    settings = engine_settings(args, shared_config())
    models = engine_models(settings)
    engine = None
    if models is None and not args.check:
        engine = start_engine(settings)
        models = wait_until_ready(settings, engine)
    if args.check:
        if models is None:
            log(f"no engine at {settings['origin']}")
            return 1
        print(json.dumps({"origin": settings["origin"], "models": models}, indent=2))
        return 0
    if not models:
        stop_owned(engine)
        raise SystemExit("the engine reports no models")
    model_id = settings["model_id"] or models[0]
    command = strip_separator(args.acp)
    if not command:
        stop_owned(engine)
        raise SystemExit("no ACP command; put the agent command after -- (see air/README.md)")
    env = dict(os.environ,
               OPENAI_BASE_URL=settings["origin"] + "/v1",
               OPENAI_API_KEY=settings["api_key"],
               OPENAI_MODEL=model_id)
    log(f"launching ACP agent with model {model_id}")
    try:
        agent = subprocess.Popen(command, env=env)
    except OSError as exc:
        stop_owned(engine)
        raise SystemExit(f"cannot start the ACP agent: {exc}") from exc

    def forward(signum, _frame):
        if agent.poll() is None:
            agent.terminate()

    for name in ("SIGTERM", "SIGINT", "SIGHUP"):
        if hasattr(signal, name):
            try:
                signal.signal(getattr(signal, name), forward)
            except (ValueError, OSError):
                pass
    try:
        code = agent.wait()
    finally:
        stop_owned(engine)
    return code if code >= 0 else 1


if __name__ == "__main__":
    sys.exit(main())
