"""Lifecycle tests for the lazy, self-stopping Air ACP bridge (stdlib only)."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

AIR = Path(__file__).resolve().parent.parent
BRIDGE = AIR / "colibri-acp.py"

FAKE_ENGINE = r'''
import argparse, json, os, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if os.environ.get("FAKE_ENGINE_ENV"):
    with open(os.environ["FAKE_ENGINE_ENV"], "w", encoding="utf-8") as fh:
        json.dump({k: os.environ.get(k) for k in ("COLI_VULKAN", "COLI_CUDA", "COLI_METAL")}, fh)

ap = argparse.ArgumentParser()
ap.add_argument("serve", nargs="?")
ap.add_argument("--model")
ap.add_argument("--host", default="127.0.0.1")
ap.add_argument("--port", type=int, default=8184)
ap.add_argument("--policy")
ap.add_argument("--ctx")
ap.add_argument("--api-key")
ap.add_argument("--auto-tier", action="store_true")
args = ap.parse_args()

MODEL = "fake-colibri"

def record(model):
    path = os.environ.get("FAKE_ENGINE_REQS")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"model": model}) + "\n")

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.split("?")[0] != "/v1/models":
            self.send_error(404)
            return
        body = json.dumps({"object": "list", "data": [{"id": MODEL}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            data = {}
        record(data.get("model"))
        delay = float(os.environ.get("FAKE_ENGINE_DELAY") or 0)
        if data.get("model") != MODEL:
            body = json.dumps({"error": {"message": "The model does not exist.", "type": "invalid_request_error",
                                         "param": "model", "code": "model_not_found"}}).encode()
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if data.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            self.wfile.flush()
            if delay:
                time.sleep(delay)
            for piece in (b"data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n",
                          b"data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n",
                          b"data: [DONE]\n\n"):
                self.wfile.write(b"%x\r\n" % len(piece) + piece + b"\r\n")
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            return
        body = json.dumps({"id": "cmpl-1", "object": "chat.completion", "model": MODEL,
                           "choices": [{"index": 0, "message": {"role": "assistant", "content": "hello"}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

server = ThreadingHTTPServer((args.host, args.port), Handler)
port_file = os.environ.get("FAKE_ENGINE_PORT_FILE")
if port_file:
    with open(port_file, "w", encoding="utf-8") as fh:
        fh.write(str(server.server_address[1]))
server.serve_forever()
'''

HELPERS = r'''
import json, os, socket, sys, time, urllib.request

BASE = os.environ["OPENAI_BASE_URL"]
ADVERTISED = int(os.environ["ENGINE_PORT"])

def engine_port():
    path = os.environ.get("ENGINE_PORT_FILE")
    if path and os.path.exists(path):
        return int(open(path).read().strip())
    return ADVERTISED

def engine_started():
    path = os.environ.get("ENGINE_PORT_FILE")
    return bool(path) and os.path.exists(path)

def up(port=None):
    with socket.socket() as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(("127.0.0.1", port or engine_port())) == 0

def wait_up(limit=10):
    deadline = time.monotonic() + limit
    while not up() and time.monotonic() < deadline:
        time.sleep(0.1)

def chat(stream=False):
    body = json.dumps({"model": os.environ["OPENAI_MODEL"], "stream": stream,
                       "messages": [{"role": "user", "content": "hi"}]}).encode()
    request = urllib.request.Request(BASE + "/chat/completions", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.status, response.read()

def report(**data):
    with open(os.environ["ACP_PROBE"], "w", encoding="utf-8") as fh:
        json.dump(data, fh)
    sys.exit(3)
'''

LAZY = HELPERS + r'''
time.sleep(0.8)
before = engine_started()
with urllib.request.urlopen(BASE + "/models", timeout=10) as response:
    models = [item["id"] for item in json.loads(response.read())["data"]]
after_models = engine_started()
status, body = chat()
during = up()
report(engine_before=before, engine_after_models=after_models, engine_during=during,
       models=models, status=status, base=BASE,
       reply=json.loads(body)["choices"][0]["message"]["content"])
'''

IDLE = HELPERS + r'''
first, _ = chat()
started = up()
time.sleep(4.5)
stopped = not up()
second, _ = chat()
restarted = up()
report(first=first, started=started, stopped=stopped, second=second, restarted=restarted)
'''

ATTACHED = HELPERS + r'''
status, _ = chat()
time.sleep(3.5)
report(status=status, still_up=up())
'''

ADOPTED = HELPERS + r'''
wait_up()
status, _ = chat()
time.sleep(4.0)
report(status=status, still_up=up())
'''

CHAT_ENV = HELPERS + r'''
status, body = chat()
with open(os.environ["ACP_PROBE"], "w", encoding="utf-8") as fh:
    json.dump({"base": BASE, "key": os.environ.get("OPENAI_API_KEY"),
               "model": os.environ.get("OPENAI_MODEL"), "status": status,
               "reply": json.loads(body)["choices"][0]["message"]["content"]}, fh)
sys.exit(3)
'''

STREAM = HELPERS + r'''
status, body = chat(stream=True)
report(status=status, body=body.decode("utf-8", "replace"))
'''

KEEPALIVE = HELPERS + r'''
body = json.dumps({"model": os.environ["OPENAI_MODEL"], "stream": True,
                   "messages": [{"role": "user", "content": "hi"}]}).encode()
request = urllib.request.Request(BASE + "/chat/completions", data=body,
                                 headers={"Content-Type": "application/json"})
start = time.time()
chunks = []
data_at = None
with urllib.request.urlopen(request, timeout=90) as response:
    headers_at = time.time() - start
    while True:
        chunk = response.read1(65536)
        if not chunk:
            break
        chunks.append(chunk)
        if data_at is None and b"hel" in chunk:
            data_at = time.time() - start
report(headers_at=headers_at, data_at=data_at,
       body=b"".join(chunks).decode("utf-8", "replace"))
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(0.25)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def bridge_env(tmp, **overrides):
    env = dict(os.environ)
    for key in ("COLIBRI_ENGINE_URL", "COLIBRI_CONFIG", "COLIBRI_API_KEY", "COLI_API_KEY",
                "COLIBRI_MODEL", "COLIBRI_PYTHON", "COLIBRI_HOME", "OPENAI_MODEL",
                "COLIBRI_BACKEND", "COLI_VULKAN", "COLI_CUDA", "COLI_METAL",
                "COLIBRI_ACP_IDLE_TIMEOUT", "COLIBRI_ACP_TIMEOUT", "COLIBRI_ACP_STATE",
                "FAKE_ENGINE_ENV", "FAKE_ENGINE_REQS", "FAKE_ENGINE_PORT_FILE",
                "FAKE_ENGINE_DELAY", "ENGINE_PORT", "ENGINE_PORT_FILE"):
        env.pop(key, None)
    env.update(COLIBRI_CONFIG=str(Path(tmp) / "missing.json"), TEMP=str(tmp), TMP=str(tmp),
               ACP_PROBE=str(Path(tmp) / "probe.json"), COLIBRI_ACP_TIMEOUT="30",
               COLIBRI_ACP_STATE=str(Path(tmp) / "state"))
    env.update(overrides)
    return env


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.model = self.dir / "model"
        self.model.mkdir()
        self.fake = self.dir / "fake_engine.py"
        self.fake.write_text(FAKE_ENGINE, encoding="utf-8")
        self.probe_file = self.dir / "probe.json"

    def run_bridge(self, *extra, port, env=None, timeout=90):
        return subprocess.run(
            [sys.executable, str(BRIDGE), "--engine-url", f"http://127.0.0.1:{port}",
             "--model", str(self.model), "--python", sys.executable,
             "--launcher", str(self.fake), *extra],
            env=env or bridge_env(self.dir), capture_output=True, text=True, timeout=timeout)

    def probe(self):
        return json.loads(self.probe_file.read_text(encoding="utf-8"))

    def start_fake(self, port, env=None):
        server = subprocess.Popen([sys.executable, str(self.fake), "serve", "--port", str(port)],
                                  env=bridge_env(self.dir, **(env or {})))

        def stop_server():
            if server.poll() is None:
                server.kill()
                server.wait(timeout=10)

        self.addCleanup(stop_server)
        deadline = time.monotonic() + 10
        while not port_open(port) and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertTrue(port_open(port), "fake engine did not start")
        return server

    def test_check_reports_a_silent_endpoint(self):
        result = self.run_bridge("--check", port=free_port())
        self.assertEqual(result.returncode, 1)
        self.assertIn("no engine", result.stderr)

    def test_engine_stays_down_until_the_first_chat_request(self):
        port = free_port()
        port_file = self.dir / "engine_port"
        env = bridge_env(self.dir, ENGINE_PORT=str(port), ENGINE_PORT_FILE=str(port_file),
                         FAKE_ENGINE_PORT_FILE=str(port_file),
                         FAKE_ENGINE_REQS=str(self.dir / "reqs.log"))
        result = self.run_bridge("--", sys.executable, "-c", LAZY, port=port, env=env)
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertFalse(data["engine_before"], "the engine must not start when the agent launches")
        self.assertEqual(data["models"], ["model"], "GET /v1/models is answered without an engine")
        self.assertFalse(data["engine_after_models"], "listing models must not start the engine")
        self.assertTrue(data["engine_during"], "the first chat request starts the engine")
        self.assertEqual(data["base"], f"http://127.0.0.1:{port}/v1",
                         "the agent is pointed at the configured engine URL")
        self.assertEqual(data["status"], 200)
        self.assertEqual(data["reply"], "hello")
        reqs = [json.loads(line) for line in (self.dir / "reqs.log").read_text().splitlines()]
        self.assertEqual(reqs, [{"model": "fake-colibri"}], "the bridge rewrites the declared id")
        engine_port = int(port_file.read_text().strip())
        self.assertNotEqual(engine_port, port, "the engine runs beside the proxy, not on it")
        deadline = time.monotonic() + 5
        while port_open(engine_port) and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertFalse(port_open(engine_port), "the bridge must stop the engine it started")
        self.assertFalse(port_open(port), "the proxy socket closes with the bridge")
        self.assertFalse((self.dir / "state" / f"engine-{port}.json").exists())

    def test_explicit_model_id_is_passed_to_the_agent(self):
        port = free_port()
        result = self.run_bridge("--model-id", "custom-id", "--", sys.executable, "-c", CHAT_ENV,
                                 port=port, env=bridge_env(self.dir, ENGINE_PORT=str(port)))
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertEqual(data["model"], "custom-id")
        self.assertEqual(data["base"], f"http://127.0.0.1:{port}/v1",
                         "OPENAI_BASE_URL points at the advertised engine URL")
        self.assertEqual(data["key"], "local")
        self.assertEqual(data["status"], 200)
        self.assertEqual(data["reply"], "hello")

    def test_idle_timeout_stops_the_engine_and_the_next_request_restarts_it(self):
        port = free_port()
        reqs = self.dir / "reqs.log"
        port_file = self.dir / "engine_port"
        env = bridge_env(self.dir, ENGINE_PORT=str(port), ENGINE_PORT_FILE=str(port_file),
                         FAKE_ENGINE_PORT_FILE=str(port_file), FAKE_ENGINE_REQS=str(reqs))
        result = self.run_bridge("--idle-timeout", "2", "--", sys.executable, "-c", IDLE,
                                 port=port, env=env)
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertEqual(data["first"], 200)
        self.assertTrue(data["started"])
        self.assertTrue(data["stopped"], "an idle owned engine must be stopped to free memory")
        self.assertEqual(data["second"], 200, result.stderr)
        self.assertTrue(data["restarted"], "the next request starts the engine again")
        self.assertIn("engine idle for", result.stderr)
        self.assertEqual(len(reqs.read_text().splitlines()), 2)

    def test_attached_engine_is_never_stopped(self):
        port = free_port()
        self.start_fake(port)
        result = self.run_bridge("--idle-timeout", "1", "--", sys.executable, "-c", ATTACHED,
                                 port=port, env=bridge_env(self.dir, ENGINE_PORT=str(port)))
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertEqual(data["status"], 200)
        self.assertTrue(data["still_up"], "an engine the bridge did not start stays warm")
        self.assertTrue(port_open(port))
        self.assertIn("attaching and leaving it warm", result.stderr)

    def test_engine_left_by_a_killed_bridge_is_adopted_and_stopped_when_idle(self):
        port = free_port()
        engine_port = free_port()
        server = self.start_fake(engine_port)
        port_file = self.dir / "engine_port"
        port_file.write_text(str(engine_port), encoding="utf-8")
        lease = self.dir / "state" / f"engine-{port}.json"
        lease.parent.mkdir(parents=True, exist_ok=True)
        lease.write_text(json.dumps({"pid": 999999999, "engine": server.pid,
                                     "engine_origin": f"http://127.0.0.1:{engine_port}",
                                     "origin": f"http://127.0.0.1:{port}"}), encoding="utf-8")
        env = bridge_env(self.dir, ENGINE_PORT=str(port), ENGINE_PORT_FILE=str(port_file))
        result = self.run_bridge("--idle-timeout", "1", "--", sys.executable, "-c", ADOPTED,
                                 port=port, env=env)
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertEqual(data["status"], 200)
        self.assertFalse(data["still_up"], "an adopted engine is stopped when idle, not left running")
        self.assertIn("adopted an engine", result.stderr)
        self.assertFalse(lease.exists())

    def spawn_env(self, *extra, env=None):
        port = free_port()
        dump = self.dir / "engine_env.json"
        result = self.run_bridge(*extra, port=port, env=bridge_env(
            self.dir, ENGINE_PORT=str(port), FAKE_ENGINE_ENV=str(dump), **(env or {})))
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertTrue(dump.is_file(), result.stderr)
        return json.loads(dump.read_text(encoding="utf-8"))

    def test_backend_flag_sets_engine_environment(self):
        data = self.spawn_env("--backend", "vulkan", "--", sys.executable, "-c", CHAT_ENV)
        self.assertEqual(data, {"COLI_VULKAN": "1", "COLI_CUDA": "0", "COLI_METAL": "0"})

    def test_backend_comes_from_shared_config(self):
        cfg = self.dir / "config.json"
        cfg.write_text(json.dumps({"backend": "metal"}), encoding="utf-8")
        data = self.spawn_env("--", sys.executable, "-c", CHAT_ENV, env={"COLIBRI_CONFIG": str(cfg)})
        self.assertEqual(data, {"COLI_VULKAN": "0", "COLI_CUDA": "0", "COLI_METAL": "1"})

    def test_auto_backend_leaves_engine_default_untouched(self):
        data = self.spawn_env("--", sys.executable, "-c", CHAT_ENV)
        self.assertEqual(data, {"COLI_VULKAN": None, "COLI_CUDA": None, "COLI_METAL": None})

    def test_invalid_backend_is_rejected(self):
        result = self.run_bridge("--backend", "quantum", "--check", port=free_port())
        self.assertEqual(result.returncode, 1)
        self.assertIn("backend must be", result.stderr)

    def test_streaming_responses_pass_through(self):
        port = free_port()
        result = self.run_bridge("--", sys.executable, "-c", STREAM, port=port,
                                 env=bridge_env(self.dir, ENGINE_PORT=str(port)))
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertEqual(data["status"], 200)
        self.assertIn("hel", data["body"])
        self.assertIn("lo", data["body"])
        self.assertIn("[DONE]", data["body"])

    def test_stream_is_held_open_while_the_engine_prefills(self):
        port = free_port()
        env = bridge_env(self.dir, ENGINE_PORT=str(port), FAKE_ENGINE_DELAY="7")
        result = self.run_bridge("--", sys.executable, "-c", KEEPALIVE, port=port, env=env)
        self.assertEqual(result.returncode, 3, result.stderr)
        data = self.probe()
        self.assertLess(data["headers_at"], 2.0,
                        "the proxy answers at once instead of waiting for the engine's first token")
        self.assertIsNotNone(data["data_at"], "engine tokens still arrive")
        self.assertGreaterEqual(data["data_at"], 6.5, "the engine really was slow")
        self.assertIn('"delta":{}', data["body"],
                      "empty keepalive chunks are sent while the engine stays silent")
        self.assertIn("hel", data["body"])
        self.assertIn("[DONE]", data["body"])

    def test_non_loopback_endpoint_is_refused(self):
        result = subprocess.run(
            [sys.executable, str(BRIDGE), "--engine-url", "http://10.0.0.5:8184", "--check"],
            env=bridge_env(self.dir), capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 1)
        self.assertIn("local engine", result.stderr)

    def test_missing_acp_command_is_reported(self):
        result = self.run_bridge(port=free_port())
        self.assertEqual(result.returncode, 1)
        self.assertIn("no ACP command", result.stderr)

    def test_help_runs_without_an_engine(self):
        result = subprocess.run([sys.executable, str(BRIDGE), "--help"],
                                env=bridge_env(self.dir), capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--engine-url", result.stdout)
        self.assertIn("--idle-timeout", result.stdout)


if __name__ == "__main__":
    unittest.main()
