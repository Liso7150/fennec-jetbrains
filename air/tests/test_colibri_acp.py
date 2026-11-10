"""Lifecycle tests for the on-demand Air ACP bridge (stdlib only)."""
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
import argparse, json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/v1/models":
            self.send_error(404)
            return
        body = json.dumps({"object": "list", "data": [{"id": "fake-colibri"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
'''

PROBE = r'''
import json, os, sys
Path = __import__("pathlib").Path
Path(os.environ["ACP_PROBE"]).write_text(json.dumps({
    "base": os.environ.get("OPENAI_BASE_URL"),
    "key": os.environ.get("OPENAI_API_KEY"),
    "model": os.environ.get("OPENAI_MODEL"),
}), encoding="utf-8")
sys.exit(3)
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(0.25)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def bridge_env(tmp):
    env = dict(os.environ)
    for key in ("COLIBRI_ENGINE_URL", "COLIBRI_CONFIG", "COLIBRI_API_KEY", "COLI_API_KEY",
                "COLIBRI_MODEL", "COLIBRI_PYTHON", "COLIBRI_HOME", "OPENAI_MODEL"):
        env.pop(key, None)
    env.update(COLIBRI_CONFIG=str(Path(tmp) / "missing.json"), TEMP=str(tmp), TMP=str(tmp),
               ACP_PROBE=str(Path(tmp) / "probe.json"), COLIBRI_ACP_TIMEOUT="30")
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

    def run_bridge(self, *extra, port):
        return subprocess.run(
            [sys.executable, str(BRIDGE), "--engine-url", f"http://127.0.0.1:{port}",
             "--model", str(self.model), "--python", sys.executable,
             "--launcher", str(self.fake), *extra],
            env=bridge_env(self.dir), capture_output=True, text=True, timeout=90)

    def test_check_reports_a_silent_endpoint(self):
        result = self.run_bridge("--check", port=free_port())
        self.assertEqual(result.returncode, 1)
        self.assertIn("no engine", result.stderr)

    def test_starts_engine_for_the_agent_and_stops_it_afterwards(self):
        port = free_port()
        probe = self.dir / "probe.json"
        result = self.run_bridge("--", sys.executable, "-c", PROBE, port=port)
        self.assertEqual(result.returncode, 3, result.stderr)
        data = json.loads(probe.read_text(encoding="utf-8"))
        self.assertEqual(data["base"], f"http://127.0.0.1:{port}/v1")
        self.assertEqual(data["key"], "local")
        self.assertEqual(data["model"], "fake-colibri")
        deadline = time.monotonic() + 5
        while port_open(port) and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertFalse(port_open(port), "the bridge must stop the engine it started")

    def test_attached_engine_is_never_stopped(self):
        port = free_port()
        server = subprocess.Popen([sys.executable, str(self.fake), "serve", "--port", str(port)],
                                  env=bridge_env(self.dir))

        def stop_server():
            if server.poll() is None:
                server.kill()
                server.wait(timeout=10)

        self.addCleanup(stop_server)
        deadline = time.monotonic() + 10
        while not port_open(port) and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertTrue(port_open(port), "fake engine did not start")
        result = self.run_bridge("--check", port=port)
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["models"], ["fake-colibri"])
        probe = self.dir / "probe.json"
        result = self.run_bridge("--", sys.executable, "-c", PROBE, port=port)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertTrue(port_open(port), "an attached engine must keep running")
        self.assertTrue(probe.is_file())

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


if __name__ == "__main__":
    unittest.main()
