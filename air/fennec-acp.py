#!/usr/bin/env python3
"""Fennec ACP bridge for JetBrains Air: a lazy, self-stopping engine proxy.

Air speaks ACP over stdio. The bridge **serves the configured engine URL**
(http://127.0.0.1:8184 by default) and starts the ACP agent (Qwen Code by
default) against that same URL. Qwen's settings pin the model endpoint there,
so the bridge must live on the advertised port, not on a helper port. The
real engine runs on a separate loopback port and is started on the first
proxied request, not when Air launches the agent. An engine the bridge owns is
stopped after --idle-timeout seconds without requests or when the agent exits,
so an idle Air window costs no model memory. If an engine already answers on
the configured URL (the desktop app, another bridge), the bridge attaches and
never stops it. A lease file lets the next bridge adopt an engine left behind
when a bridge was killed hard, so it too stops when idle.

The JetBrains plugin registers this bridge as the Fennec Desktop agent card;
~/.jetbrains/acp.json is no longer involved.

Everything after -- is the ACP agent command. Stdlib only; all paths are local.
"""
from __future__ import annotations

import argparse
import http.client
import http.server
import ipaddress
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

START_TIMEOUT_S = int(os.environ.get("FENNEC_ACP_TIMEOUT", "900"))
REQUEST_TIMEOUT_S = int(os.environ.get("FENNEC_ACP_REQUEST_TIMEOUT", "900"))
POLL_S = 1.0
DEFAULT_IDLE_S = 300
HOP_BY_HOP = frozenset({
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
})


def log(message):
    print(f"[fennec-acp] {message}", file=sys.stderr, flush=True)


class BridgeError(Exception):
    def __init__(self, message, status=503):
        super().__init__(message)
        self.status = status


def is_loopback(host):
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def shared_config():
    path = Path(os.environ.get("FENNEC_CONFIG") or Path.home() / ".fennec" / "config.json")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as exc:
        raise SystemExit(f"cannot read {path}: {exc}")


def engine_settings(args, cfg):
    raw_url = (args.engine_url or os.environ.get("FENNEC_ENGINE_URL")
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
    engine_root = Path(os.environ.get("FENNEC_HOME") or cfg.get("engine_root") or Path.home() / "Colibri")
    windows = os.name == "nt"
    python = (args.python or os.environ.get("FENNEC_PYTHON") or cfg.get("python")
              or engine_root / ".venv" / ("Scripts/python.exe" if windows else "bin/python"))
    settings = {
        "origin": f"http://{host}:{port}",
        "host": host,
        "port": port,
        "engine_root": str(engine_root),
        "model": args.model or os.environ.get("FENNEC_MODEL") or cfg.get("model") or "",
        "python": str(python),
        "launcher": str(args.launcher or engine_root / "c" / "coli"),
        "policy": args.policy or cfg.get("policy") or "balanced",
        "backend": str(args.backend or os.environ.get("FENNEC_BACKEND") or cfg.get("backend") or "auto").strip().lower(),
        "ctx": args.ctx if args.ctx is not None else int(cfg.get("ctx") or 0),
        "api_key": os.environ.get("FENNEC_API_KEY") or os.environ.get("COLI_API_KEY") or "local",
    }
    if settings["backend"] not in ("auto", "vulkan", "cuda", "metal", "cpu"):
        raise SystemExit("backend must be auto, vulkan, cuda, metal or cpu")
    settings["model_id"] = args.model_id or os.environ.get("OPENAI_MODEL") or str(cfg.get("model_id") or "")
    return settings


def idle_seconds(args, cfg):
    if args.idle_timeout is not None:
        value = args.idle_timeout
    elif os.environ.get("FENNEC_ACP_IDLE_TIMEOUT"):
        try:
            value = int(os.environ["FENNEC_ACP_IDLE_TIMEOUT"])
        except ValueError:
            raise SystemExit("FENNEC_ACP_IDLE_TIMEOUT must be a number of seconds")
    else:
        try:
            value = int(cfg.get("idle_timeout") or DEFAULT_IDLE_S)
        except (TypeError, ValueError):
            raise SystemExit("idle_timeout in the shared configuration must be a number of seconds")
    if value < 0:
        raise SystemExit("idle timeout must be zero (never stop) or a number of seconds")
    return value


def free_port(host):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((host, 0))
        return sock.getsockname()[1]


def lease_path_for(settings):
    return (Path(os.environ.get("FENNEC_ACP_STATE") or Path.home() / ".fennec" / "air")
            / f"engine-{settings['port']}.json")


def read_lease(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def engine_models(origin, api_key):
    """Return the engine's model ids, or None when nothing healthy answers."""
    request = urllib.request.Request(origin + "/v1/models")
    if api_key:
        request.add_header("Authorization", "Bearer " + api_key)
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


def validate_start(settings):
    if not settings["model"] or not Path(settings["model"]).is_dir():
        raise SystemExit("no local model configured; pass --model /path/to/model or set model in ~/.fennec/config.json")
    if not Path(settings["python"]).is_file():
        raise SystemExit(f"Python runtime not found: {settings['python']}")
    if not Path(settings["launcher"]).is_file():
        raise SystemExit(f"Fennec launcher not found: {settings['launcher']}")


def compute_model_id(settings):
    """Ask the engine's own family registry for the id coli serve will announce."""
    model = settings["model"]
    family_dir = Path(settings["engine_root"]) / "c"
    if not model or not Path(model).is_dir() or not (family_dir / "family_registry.py").is_file():
        return None
    script = (
        "import sys\n"
        "sys.path.insert(0, sys.argv[1])\n"
        "import family_registry as fr\n"
        "print(fr.default_model_id(fr.resolve_model(sys.argv[2])))\n"
    )
    try:
        done = subprocess.run([settings["python"], "-c", script, str(family_dir), str(Path(model))],
                              capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    lines = done.stdout.strip().splitlines()
    return lines[-1].strip() if lines else None


def resolve_model_id(settings, running_models):
    if settings["model_id"]:
        return settings["model_id"]
    if running_models:
        return running_models[0]
    computed = compute_model_id(settings)
    if computed:
        log(f"model id from the engine's family registry: {computed}")
        return computed
    if settings["model"]:
        return Path(settings["model"]).name
    return ""


def start_engine(settings, engine_origin):
    if not settings["model"] or not Path(settings["model"]).is_dir():
        raise BridgeError("no local model configured; pass --model /path/to/model or set model in ~/.fennec/config.json")
    if not Path(settings["python"]).is_file():
        raise BridgeError(f"Python runtime not found: {settings['python']}")
    if not Path(settings["launcher"]).is_file():
        raise BridgeError(f"Fennec launcher not found: {settings['launcher']}")
    target = urlsplit(engine_origin)
    cmd = [settings["python"], settings["launcher"], "serve",
           "--model", settings["model"], "--auto-tier",
           "--policy", settings["policy"], "--ctx", str(settings["ctx"]),
           "--host", target.hostname, "--port", str(target.port),
           "--api-key", settings["api_key"]]
    log_file = Path(os.environ.get("TEMP") or os.environ.get("TMPDIR") or "/tmp") / "fennec-acp-engine.log"
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    backend = settings.get("backend") or "auto"
    if backend != "auto":
        # serve has no --backend flag; the engine reads these variables at startup.
        env.update(COLI_VULKAN="0", COLI_CUDA="0", COLI_METAL="0")
        env["COLI_" + backend.upper()] = "1"
    log(f"starting local engine on {engine_origin} (backend {backend}, log: {log_file})")
    with open(log_file, "ab") as stream:
        return subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=stream, stderr=stream,
                                env=env, start_new_session=os.name != "nt")


def wait_until_ready(engine_origin, api_key, engine):
    deadline = time.monotonic() + START_TIMEOUT_S
    tick = 0
    while time.monotonic() < deadline:
        models = engine_models(engine_origin, api_key)
        if models is not None:
            return models
        if engine.poll() is not None:
            raise BridgeError(f"the engine exited during startup; see {Path(os.environ.get('TEMP') or '/tmp') / 'fennec-acp-engine.log'}")
        tick += 1
        if tick % 5 == 0:
            log(f"loading model... ({int(tick * POLL_S)}s)")
        time.sleep(POLL_S)
    raise BridgeError("engine did not become ready in time")


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


def stop_pid(pid):
    if pid <= 0:
        return
    log("stopping the adopted engine")
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       capture_output=True, check=False)
        return
    try:
        os.killpg(pid, signal.SIGTERM)
    except OSError:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass


def pid_alive(pid):
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            if kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return code.value == 259  # STILL_ACTIVE
            return False
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


class EngineSupervisor:
    """Owns the engine behind the proxy: lazy start, idle stop, safe attach."""

    def __init__(self, settings, declared_model, idle_timeout, engine_origin, adopted_pid=None):
        self.settings = settings
        self.declared_model = declared_model
        self.actual_model = declared_model
        self.idle_timeout = idle_timeout
        self.engine_origin = engine_origin
        self.lock = threading.RLock()
        self.process = None
        self.engine_pid = adopted_pid
        self.owned = adopted_pid is not None
        self.classified = adopted_pid is not None
        self.inflight = 0
        self.last_activity = time.monotonic()
        self.rewrote = False
        self.lease_path = lease_path_for(settings)
        if adopted_pid is not None:
            self._write_lease()
            log("adopted an engine left behind by an earlier bridge; it stops when idle")

    # --- engine lifecycle -------------------------------------------------
    def running(self):
        if self.process is not None and self.process.poll() is None:
            return True
        return engine_models(self.engine_origin, self.settings["api_key"]) is not None

    def ensure_engine(self):
        with self.lock:
            if self.process is not None and self.process.poll() is not None:
                log("the engine exited; it will start again on the next request")
                self.process = None
                self.engine_pid = None
                self.owned = False
                self.classified = False
                self._drop_lease()
            if self.process is not None:
                try:
                    models = wait_until_ready(self.engine_origin, self.settings["api_key"], self.process)
                except BridgeError:
                    self._stop("the running engine stopped answering; stopping it")
                    raise
                self._remember(models)
                return
            models = engine_models(self.engine_origin, self.settings["api_key"])
            if models is not None:
                self._remember(models)
                if not self.classified:
                    self._classify_attached()
                return
            self._start()

    def _start(self):
        self.process = start_engine(self.settings, self.engine_origin)
        self.engine_pid = self.process.pid
        self.owned = True
        self.classified = True
        self._write_lease()
        try:
            models = wait_until_ready(self.engine_origin, self.settings["api_key"], self.process)
        except BridgeError:
            self._stop("the engine did not become ready; stopping it")
            raise
        self._remember(models)
        if self.idle_timeout:
            log(f"engine ready; it stops after {self.idle_timeout}s without requests")
        else:
            log("engine ready; idle stop is disabled")

    def _remember(self, models):
        if models:
            if models[0] != self.actual_model:
                log(f"the engine reports model id {models[0]}")
            self.actual_model = models[0]

    def _classify_attached(self):
        self.classified = True
        lease = read_lease(self.lease_path)
        pid = int(lease.get("pid") or 0) if lease else 0
        engine_pid = int(lease.get("engine") or 0) if lease else 0
        if pid == os.getpid():
            self.owned = True
            self.engine_pid = engine_pid or self.engine_pid
            return
        if pid and pid_alive(pid):
            self.owned = False
            log("attached to an engine owned by another Fennec bridge; leaving it to that bridge")
        elif pid and engine_pid:
            self.owned = True
            self.engine_pid = engine_pid
            self._write_lease()
            log("adopted an engine left behind by an earlier bridge; it stops when idle")
        else:
            self.owned = False
            log("attached to an already running engine; it stays warm after this session")

    def maybe_stop_idle(self):
        with self.lock:
            if not self.owned or self.inflight or self.idle_timeout <= 0:
                return
            if self.process is None and self.engine_pid is None:
                return
            idle = time.monotonic() - self.last_activity
            if idle < self.idle_timeout:
                return
            log(f"engine idle for {int(idle)}s; stopping it to free memory (the next request starts it again)")
            self._stop(None)

    def _stop(self, reason):
        if reason:
            log(reason)
        if self.process is not None:
            stop_owned(self.process)
        elif self.engine_pid:
            stop_pid(self.engine_pid)
        self.process = None
        self.engine_pid = None
        self.owned = False
        self.classified = False
        self._drop_lease()

    def shutdown(self):
        with self.lock:
            if self.owned:
                self._stop(None)

    # --- lease: survives a bridge that was killed hard --------------------
    def _write_lease(self):
        engine_pid = self.process.pid if self.process is not None else self.engine_pid
        try:
            self.lease_path.parent.mkdir(parents=True, exist_ok=True)
            self.lease_path.write_text(json.dumps({"pid": os.getpid(),
                                                   "engine": engine_pid,
                                                   "engine_origin": self.engine_origin,
                                                   "origin": self.settings["origin"],
                                                   "started": int(time.time())}), encoding="utf-8")
        except OSError as exc:
            log(f"cannot write the engine lease {self.lease_path}: {exc}")

    def _drop_lease(self):
        try:
            self.lease_path.unlink()
        except OSError:
            pass

    # --- request plumbing --------------------------------------------------
    def begin(self):
        with self.lock:
            self.inflight += 1
            self.last_activity = time.monotonic()

    def end(self):
        with self.lock:
            self.inflight = max(0, self.inflight - 1)
            self.last_activity = time.monotonic()

    def models_payload(self):
        model = self.actual_model or self.declared_model
        return json.dumps({"object": "list", "data": [
            {"id": model, "object": "model", "created": int(time.time()), "owned_by": "fennec"}
        ]}).encode()

    def rewrite_body(self, headers, body):
        if not body:
            return body
        content_type = ""
        for name, value in headers.items():
            if name.lower() == "content-type":
                content_type = value.lower()
                break
        if "json" not in content_type or not self.actual_model:
            return body
        try:
            data = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            return body
        if not isinstance(data, dict) or data.get("model") == self.actual_model or "model" not in data:
            return body
        data["model"] = self.actual_model
        if not self.rewrote:
            log(f"engine serves {self.actual_model}; request model names are rewritten to match")
            self.rewrote = True
        return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def open(self, method, path, headers, body):
        self.ensure_engine()
        body = self.rewrite_body(headers, body)
        target = urlsplit(self.engine_origin)
        host, port = target.hostname, target.port or 80
        connection = http.client.HTTPConnection(host, port, timeout=REQUEST_TIMEOUT_S)
        forward = {name: value for name, value in headers.items()
                   if name.lower() not in HOP_BY_HOP and name.lower() not in ("host", "content-length")}
        forward["Host"] = f"{host}:{port}"
        forward["Connection"] = "close"
        if body or method in ("POST", "PUT", "PATCH"):
            forward["Content-Length"] = str(len(body))
        try:
            connection.request(method, path, body=body or None, headers=forward)
            response = connection.getresponse()
        except (OSError, http.client.HTTPException) as exc:
            connection.close()
            with self.lock:
                if self.process is not None and self.process.poll() is not None:
                    self.process = None
                    self.owned = False
                    self._drop_lease()
            raise BridgeError(f"cannot reach the Colibri engine at {self.engine_origin}: {exc}")
        return response.status, list(response.headers.items()), response, connection


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        self._forward()

    def do_POST(self):
        self._forward()

    def do_PUT(self):
        self._forward()

    def do_PATCH(self):
        self._forward()

    def do_DELETE(self):
        self._forward()

    def _read_body(self):
        encoding = self.headers.get("Transfer-Encoding", "").lower()
        if "chunked" in encoding:
            return self._read_chunked()
        length = self.headers.get("Content-Length")
        if not length:
            return b""
        try:
            count = int(length)
        except ValueError:
            raise BridgeError("invalid Content-Length", 400)
        return self.rfile.read(count) if count > 0 else b""

    def _read_chunked(self):
        data = bytearray()
        while True:
            line = self.rfile.readline(65536)
            if not line:
                break
            try:
                size = int(line.split(b";")[0].strip(), 16)
            except ValueError:
                raise BridgeError("invalid chunked request body", 400)
            if size == 0:
                while True:
                    trailer = self.rfile.readline(65536)
                    if trailer in (b"\r\n", b"\n", b""):
                        break
                break
            data += self.rfile.read(size)
            self.rfile.read(2)
        return bytes(data)

    def _forward(self):
        supervisor = self.server.supervisor
        try:
            body = self._read_body()
        except BridgeError as exc:
            self._json(exc.status, str(exc))
            return
        supervisor.begin()
        try:
            path = self.path.split("?")[0]
            if self.command == "GET" and path == "/v1/models" and not supervisor.running():
                self._raw(200, [("Content-Type", "application/json")], supervisor.models_payload())
                return
            if self.command == "POST" and path.endswith("/chat/completions") and self._wants_stream(body):
                self._relay_stream(supervisor, body)
                return
            try:
                status, headers, response, connection = supervisor.open(self.command, self.path,
                                                                        self.headers, body)
            except BridgeError as exc:
                self._json(exc.status, str(exc))
                return
            try:
                self._stream(status, headers, response)
            except OSError:
                # The client went away mid-response (Qwen cancels side requests
                # such as title generation); nothing is left to deliver.
                pass
            finally:
                connection.close()
        finally:
            supervisor.end()

    def _wants_stream(self, body):
        if not body:
            return False
        content_type = (self.headers.get("Content-Type") or "").lower()
        if "json" not in content_type:
            return False
        try:
            data = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            return False
        return isinstance(data, dict) and data.get("stream") is True

    def _relay_stream(self, supervisor, body):
        # The engine may spend minutes on the first token of a large prompt
        # (model load + prefill), and both Node's undici and several SDKs abort
        # a silent stream after a few minutes. Answer at once and keep the SSE
        # stream alive with empty chunks until real tokens arrive.
        self.send_response_only(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        write_lock = threading.Lock()
        stop = threading.Event()
        pending = [b""]

        def frame(payload):
            with write_lock:
                self.wfile.write(b"%x\r\n" % len(payload) + payload + b"\r\n")
                self.wfile.flush()

        heartbeat = (b'data: {"id":"chatcmpl-fennec","object":"chat.completion.chunk",'
                     b'"created":0,"model":"fennec","choices":[{"index":0,"delta":{},'
                     b'"finish_reason":null}]}\n\n')

        def beat():
            # Runs for the whole stream: a CPU fallback or a stalled turn can
            # leave minutes between tokens, and the SDK aborts a silent body.
            # Never cut in while an engine line is half-forwarded.
            while not stop.wait(5.0):
                with write_lock:
                    if pending[0]:
                        continue
                    try:
                        self.wfile.write(b"%x\r\n" % len(heartbeat) + heartbeat + b"\r\n")
                        self.wfile.flush()
                    except OSError:
                        return

        thread = threading.Thread(target=beat, name="sse-heartbeat", daemon=True)
        thread.start()

        def stop_beat():
            stop.set()
            thread.join(timeout=5)

        try:
            try:
                status, headers, response, connection = supervisor.open(
                    self.command, self.path, self.headers, body)
            except BridgeError as exc:
                stop_beat()
                error = {"error": {"message": str(exc), "type": "fennec_bridge"}}
                frame(b"data: " + json.dumps(error).encode() + b"\n\n")
                frame(b"data: [DONE]\n\n")
                return
            try:
                if status >= 400:
                    stop_beat()
                    try:
                        payload = response.read(1_000_000).decode("utf-8", "replace")
                    except (OSError, http.client.HTTPException):
                        payload = ""
                    error = {"error": {"message": f"engine returned {status}: {payload[:2000]}",
                                       "type": "fennec_engine"}}
                    frame(b"data: " + json.dumps(error).encode() + b"\n\n")
                    frame(b"data: [DONE]\n\n")
                    return
                while True:
                    try:
                        chunk = response.read1(65536)
                    except http.client.IncompleteRead:
                        break
                    if not chunk:
                        break
                    with write_lock:
                        pending[0] += chunk
                        cut = pending[0].rfind(b"\n") + 1
                        if cut:
                            out, pending[0] = pending[0][:cut], pending[0][cut:]
                        else:
                            out = b""
                    if out:
                        frame(out)
                tail = pending[0]
                pending[0] = b""
                if tail:
                    frame(tail)
            finally:
                connection.close()
        except OSError:
            stop_beat()
            return
        finally:
            stop_beat()
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except OSError:
                pass

    def _stream(self, status, headers, response):
        self.send_response_only(status)
        for name, value in headers:
            if name.lower() in HOP_BY_HOP or name.lower() in ("content-length", "transfer-encoding"):
                continue
            self.send_header(name, value)
        length = response.length
        if self.command == "HEAD" or status in (204, 304) or status < 200:
            if length is not None:
                self.send_header("Content-Length", str(length))
            self.end_headers()
            return
        if length is not None:
            self.send_header("Content-Length", str(length))
            self.end_headers()
            remaining = length
            while remaining > 0:
                chunk = response.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                remaining -= len(chunk)
            return
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        while True:
            try:
                chunk = response.read1(65536)
            except http.client.IncompleteRead:
                break
            if not chunk:
                break
            self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
            self.wfile.flush()
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _json(self, status, message):
        payload = json.dumps({"error": {"message": message, "type": "fennec_bridge"}}).encode()
        self._raw(status, [("Content-Type", "application/json")], payload)

    def _raw(self, status, headers, body):
        self.send_response_only(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
            self.wfile.flush()


class ProxyServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, supervisor):
        self.supervisor = supervisor
        super().__init__(address, handler)


def parser():
    ap = argparse.ArgumentParser(description="Run an ACP agent against a lazy, self-stopping Colibri engine")
    ap.add_argument("--engine-url")
    ap.add_argument("--model", help="model directory to serve when the endpoint is silent")
    ap.add_argument("--model-id", help="OPENAI_MODEL override; defaults to what the engine will announce")
    ap.add_argument("--python", help="Python runtime for the engine (defaults to <engine_root>/.venv)")
    ap.add_argument("--launcher", help="engine launcher path (defaults to <engine_root>/c/coli)")
    ap.add_argument("--policy")
    ap.add_argument("--backend", help="engine backend for on-demand starts: auto, vulkan, cuda, metal or cpu")
    ap.add_argument("--ctx", type=int)
    ap.add_argument("--idle-timeout", type=int,
                    help=f"seconds without requests before an owned engine is stopped (default {DEFAULT_IDLE_S}, 0 disables)")
    ap.add_argument("--check", action="store_true", help="report engine status without starting it or any agent")
    ap.add_argument("acp", nargs=argparse.REMAINDER, help="ACP agent command after --")
    return ap


def strip_separator(argv):
    return argv[1:] if argv and argv[0] == "--" else argv


def main(argv=None):
    args = parser().parse_args(argv)
    cfg = shared_config()
    settings = engine_settings(args, cfg)
    idle_timeout = idle_seconds(args, cfg)
    models = engine_models(settings["origin"], settings["api_key"])
    if args.check:
        if models is None:
            log(f"no engine at {settings['origin']}")
            return 1
        print(json.dumps({"origin": settings["origin"], "models": models,
                          "idle_timeout": idle_timeout}, indent=2))
        return 0
    if models is None:
        validate_start(settings)
    declared = resolve_model_id(settings, models)
    if not declared:
        raise SystemExit("cannot determine the model id; pass --model-id")
    command = strip_separator(args.acp)
    if not command:
        raise SystemExit("no ACP command; put the agent command after -- (see air/README.md)")

    lease = read_lease(lease_path_for(settings))
    server = None
    adopted_pid = None
    if models is not None:
        engine_origin = settings["origin"]
        supervisor = EngineSupervisor(settings, declared, idle_timeout, engine_origin)
        log(f"an engine already answers on {settings['origin']}; attaching and leaving it warm")
    else:
        engine_origin = ""
        if lease:
            leased_origin = str(lease.get("engine_origin") or "")
            leased_pid = int(lease.get("engine") or 0)
            if (leased_origin and leased_pid and leased_origin != settings["origin"]
                    and pid_alive(leased_pid)
                    and engine_models(leased_origin, settings["api_key"]) is not None):
                engine_origin = leased_origin
                adopted_pid = leased_pid
        if not engine_origin:
            engine_origin = f"http://{settings['host']}:{free_port(settings['host'])}"
        supervisor = EngineSupervisor(settings, declared, idle_timeout, engine_origin, adopted_pid)
        try:
            server = ProxyServer((settings["host"], settings["port"]), ProxyHandler, supervisor)
        except OSError as exc:
            models = engine_models(settings["origin"], settings["api_key"])
            if models is None:
                raise SystemExit(f"cannot listen on {settings['origin']}: {exc}") from exc
            engine_origin = settings["origin"]
            supervisor = EngineSupervisor(settings, declared, idle_timeout, engine_origin)
            log(f"an engine already answers on {settings['origin']}; attaching and leaving it warm")
        else:
            threading.Thread(target=server.serve_forever, name="fennec-proxy", daemon=True).start()
            idle_note = f"stops after {idle_timeout}s idle" if idle_timeout else "idle stop disabled"
            log(f"bridge serving {settings['origin']}/v1; the engine will run on {engine_origin} "
                f"and {idle_note}; it starts with the first request")
    log(f"agent model {declared}")

    done = threading.Event()

    def watch():
        while not done.wait(1.0):
            try:
                supervisor.maybe_stop_idle()
            except Exception as exc:  # the watchdog must never take the bridge down
                log(f"idle watchdog: {exc}")

    threading.Thread(target=watch, name="fennec-idle", daemon=True).start()
    env = dict(os.environ,
               OPENAI_BASE_URL=settings["origin"] + "/v1",
               OPENAI_API_KEY=settings["api_key"],
               OPENAI_MODEL=declared)
    log("launching the ACP agent")
    try:
        agent = subprocess.Popen(command, env=env)
    except OSError as exc:
        done.set()
        supervisor.shutdown()
        if server is not None:
            server.shutdown()
            server.server_close()
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
        done.set()
        supervisor.shutdown()
        if server is not None:
            server.shutdown()
            server.server_close()
    return code if code >= 0 else 1


if __name__ == "__main__":
    sys.exit(main())
