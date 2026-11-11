"""Tests for the Air ACP launcher: it finds fennec-core and gets out of the way.

The launcher used to carry its own ACP implementation and engine supervisor;
both live in fennec_core.acp now, so these tests cover discovery, forwarding
and the errors a user actually sees when the suite is missing. Stdlib only,
no engine, no network, no IDE.
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

AIR = Path(__file__).resolve().parent.parent
LAUNCHER = AIR / "fennec-acp.py"
CORE_ACP = AIR.parent.parent / "fennec-core" / "fennec_core" / "acp.py"


def load_launcher():
    spec = importlib.util.spec_from_file_location("fennec_air_launcher", LAUNCHER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


launcher = load_launcher()

FAKE_CORE = '''"""Fake fennec-core used by the launcher tests."""
__version__ = "0.0.0-fake"
'''

FAKE_ACP = '''"""Fake ACP agent entry point; records how the launcher called it."""
import json
import os
import sys


def main(argv=None):
    trace = os.environ.get("FENNEC_TEST_TRACE")
    if trace:
        with open(trace, "a", encoding="utf-8") as stream:
            stream.write(json.dumps(list(argv if argv is not None else sys.argv[1:])) + "\\n")
    return 7
'''


def make_core(base: Path) -> Path:
    """A minimal checkout that looks like fennec-core to the launcher."""
    package = base / "fennec_core"
    package.mkdir(parents=True, exist_ok=True)
    (package / "__init__.py").write_text(FAKE_CORE, encoding="utf-8")
    (package / "acp.py").write_text(FAKE_ACP, encoding="utf-8")
    return base


class ImportIsolation:
    """Keep the fake core and a real one from leaking across tests."""

    def __enter__(self):
        self.cached = {name: module for name, module in sys.modules.items()
                       if name == "fennec_core" or name.startswith("fennec_core.")}
        for name in self.cached:
            del sys.modules[name]
        self.path = list(sys.path)
        return self

    def __exit__(self, *exc):
        sys.path[:] = self.path
        for name in [key for key in sys.modules
                     if key == "fennec_core" or key.startswith("fennec_core.")]:
            del sys.modules[name]
        sys.modules.update(self.cached)
        return False


class LauncherCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.here = self.root / "suite" / "fennec-jetbrains" / "air"
        self.here.mkdir(parents=True)

    def environ(self, **extra):
        environ = {}
        for key in ("FENNEC_CORE", "FENNEC_SUITE", "FENNEC_CONFIG"):
            value = extra.pop(key, None)
            if value is not None:
                environ[key] = str(value)
        environ.update(extra)
        return environ

    def write_config(self, data):
        path = self.home / ".fennec" / "config.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(data if isinstance(data, str) else json.dumps(data), encoding="utf-8")
        return path


class DiscoveryTests(LauncherCase):
    def test_search_order_is_explicit_then_suite_then_sibling(self):
        explicit = make_core(self.root / "explicit")
        suite = make_core(self.root / "configured" / "fennec-core")
        self.write_config({"suite_root": str(suite.parent)})
        paths = launcher.core_search_paths(self.environ(FENNEC_CORE=explicit), self.here, self.home)
        self.assertEqual(paths, [explicit, suite, self.here.parent.parent / "fennec-core"])

    def test_fennec_core_wins_over_the_suite_root(self):
        explicit = make_core(self.root / "explicit")
        suite = make_core(self.root / "configured" / "fennec-core")
        self.write_config({"suite_root": str(suite.parent)})
        found = launcher.find_core(self.environ(FENNEC_CORE=explicit), self.here, self.home)
        self.assertEqual(found, explicit)

    def test_a_pointless_fennec_core_falls_through_to_the_suite(self):
        empty = self.root / "empty"
        empty.mkdir()
        suite = make_core(self.root / "configured" / "fennec-core")
        self.write_config({"suite_root": str(suite.parent)})
        found = launcher.find_core(self.environ(FENNEC_CORE=empty), self.here, self.home)
        self.assertEqual(found, suite)

    def test_suite_defaults_to_the_home_and_relative_values_are_home_relative(self):
        self.assertEqual(launcher.suite_root(self.environ(), {}, self.home),
                         self.home / "Fennec-Suite")
        self.assertEqual(launcher.suite_root(self.environ(), {"suite_root": "My/Suite"}, self.home),
                         self.home / "My" / "Suite")

    def test_suite_root_comes_from_the_shared_config(self):
        suite = make_core(self.root / "configured" / "fennec-core")
        self.write_config({"suite_root": str(suite.parent)})
        config = launcher.shared_config(self.environ(), self.home)
        self.assertEqual(launcher.suite_root(self.environ(), config, self.home), suite.parent)
        self.assertEqual(launcher.find_core(self.environ(), self.here, self.home), suite)

    def test_fennec_suite_environment_overrides_the_shared_config(self):
        env_suite = make_core(self.root / "env-suite" / "fennec-core")
        self.write_config({"suite_root": str(self.root / "missing")})
        found = launcher.find_core(self.environ(FENNEC_SUITE=env_suite.parent), self.here, self.home)
        self.assertEqual(found, env_suite)

    def test_a_checkout_next_to_the_launcher_needs_no_config(self):
        sibling = make_core(self.here.parent.parent / "fennec-core")
        self.assertEqual(launcher.find_core(self.environ(), self.here, self.home), sibling)

    def test_a_core_without_the_acp_agent_is_not_a_core(self):
        """A pre-ACP fennec-core has the package and not the module; choosing it is a traceback."""
        old = self.root / "old"
        (old / "fennec_core" / "__init__.py").parent.mkdir(parents=True)
        (old / "fennec_core" / "__init__.py").write_text(FAKE_CORE, encoding="utf-8")
        suite = make_core(self.root / "configured" / "fennec-core")
        self.write_config({"suite_root": str(suite.parent)})
        self.assertEqual(launcher.find_core(self.environ(FENNEC_CORE=old), self.here, self.home), suite)
        dead = self.root / "no-suite"
        with mock.patch.object(launcher.importlib.util, "find_spec", return_value=None):
            with self.assertRaises(launcher.CoreNotFound) as raised:
                launcher.import_agent(self.environ(FENNEC_CORE=old, FENNEC_SUITE=dead),
                                      self.here, self.home)
        self.assertIn(str(old), str(raised.exception))
        self.assertIn("FENNEC_CORE", str(raised.exception))

    def test_default_config_location_and_empty_config(self):
        path = self.write_config({})
        self.assertEqual(launcher.config_file(self.environ(), self.home), path)
        self.assertEqual(launcher.shared_config(self.environ(), self.home), {})

    def test_a_config_that_is_not_an_object_is_ignored(self):
        self.write_config("[1, 2]")
        self.assertEqual(launcher.shared_config(self.environ(), self.home), {})

    def test_fennec_config_pointing_nowhere_is_an_error(self):
        missing = self.home / "nope.json"
        with self.assertRaises(launcher.CoreNotFound) as raised:
            launcher.shared_config(self.environ(FENNEC_CONFIG=missing), self.home)
        self.assertIn("nope.json", str(raised.exception))

    def test_broken_json_config_names_the_file(self):
        path = self.write_config("{oops")
        with self.assertRaises(launcher.CoreNotFound) as raised:
            launcher.shared_config(self.environ(FENNEC_CONFIG=path), self.home)
        self.assertIn(str(path), str(raised.exception))

    def test_a_broken_config_stops_discovery(self):
        self.write_config("{oops")
        with self.assertRaises(launcher.CoreNotFound):
            launcher.core_search_paths(self.environ(FENNEC_CONFIG=self.home / ".fennec" / "config.json"),
                                       self.here, self.home)


class ImportTests(LauncherCase):
    def test_missing_core_lists_the_directories_it_searched(self):
        missing = self.root / "nowhere"
        with mock.patch.object(launcher.importlib.util, "find_spec", return_value=None):
            with self.assertRaises(launcher.CoreNotFound) as raised:
                launcher.import_agent(self.environ(FENNEC_CORE=missing), self.here, self.home)
        message = str(raised.exception)
        self.assertIn(str(missing), message)
        self.assertIn("FENNEC_CORE", message)
        self.assertIn("installer", message)

    def test_an_installed_package_needs_no_checkout(self):
        fake = mock.Mock()
        with mock.patch.object(launcher.importlib.util, "find_spec", return_value=object()), \
             mock.patch.object(launcher.importlib, "import_module", return_value=fake) as imported:
            self.assertIs(launcher.import_agent(self.environ(), self.here, self.home), fake)
        imported.assert_called_once_with("fennec_core.acp")

    def test_a_found_checkout_is_put_on_sys_path_before_importing(self):
        root = make_core(self.here.parent.parent / "fennec-core")
        with ImportIsolation():
            agent = launcher.import_agent(self.environ(), self.here, self.home)
            self.assertEqual(sys.path[0], str(root))
            self.assertEqual(agent.__name__, "fennec_core.acp")
            self.assertEqual(sys.modules["fennec_core"].__version__, "0.0.0-fake")

    def test_repeated_launches_do_not_stack_the_path(self):
        root = make_core(self.here.parent.parent / "fennec-core")
        with ImportIsolation():
            launcher.import_agent(self.environ(), self.here, self.home)
            launcher.import_agent(self.environ(), self.here, self.home)
            self.assertEqual(sys.path.count(str(root)), 1)


class ForwardingTests(LauncherCase):
    def setUp(self):
        super().setUp()
        self.trace = self.root / "trace.jsonl"
        self.launch_env = {"FENNEC_CORE": str(make_core(self.here.parent.parent / "fennec-core"))}
        os.environ["FENNEC_TEST_TRACE"] = str(self.trace)
        self.addCleanup(os.environ.pop, "FENNEC_TEST_TRACE", None)
        self.isolation = ImportIsolation()
        self.isolation.__enter__()
        self.addCleanup(self.isolation.__exit__, None, None, None)

    def calls(self):
        if not self.trace.exists():
            return []
        return [json.loads(line) for line in self.trace.read_text(encoding="utf-8").splitlines()]

    def launch(self, argv):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = launcher.main(argv, self.launch_env, self.here)
        return code, stdout.getvalue()

    def test_check_flag_reaches_the_core_agent(self):
        code, stdout = self.launch(["--check"])
        self.assertEqual(code, 7)
        self.assertEqual(self.calls(), [["--check"]])

    def test_no_arguments_starts_the_stdio_agent(self):
        code, stdout = self.launch([])
        self.assertEqual(code, 7)
        self.assertEqual(self.calls(), [[]])

    def test_an_unknown_flag_is_rejected_before_any_import(self):
        with mock.patch.object(launcher, "import_agent") as import_agent, \
             redirect_stderr(io.StringIO()) as stderr:
            with self.assertRaises(SystemExit) as raised:
                launcher.main(["--engine-url", "http://127.0.0.1:9"], self.launch_env, self.here)
        self.assertEqual(raised.exception.code, 2)
        import_agent.assert_not_called()
        self.assertEqual(self.calls(), [])
        self.assertIn("unrecognized arguments", stderr.getvalue())

    def test_version_reports_the_launcher_line(self):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            with self.assertRaises(SystemExit) as raised:
                launcher.main(["--version"], self.launch_env, self.here)
        self.assertEqual(raised.exception.code, 0)
        self.assertEqual(stdout.getvalue().strip(), f"fennec-acp {launcher.VERSION}")

    def test_an_installed_core_without_the_agent_is_explained_not_raised(self):
        with mock.patch.object(launcher, "import_agent",
                               side_effect=ModuleNotFoundError("No module named 'fennec_core.acp'")), \
             redirect_stderr(io.StringIO()) as stderr, redirect_stdout(io.StringIO()):
            code = launcher.main([], self.launch_env, self.here)
        self.assertEqual(code, 2)
        self.assertIn("[fennec-acp]", stderr.getvalue())
        self.assertIn("fennec_core/acp.py", stderr.getvalue())

    def test_a_missing_core_exits_two_with_a_readable_reason(self):
        here = self.root / "stray" / "fennec-jetbrains" / "air"
        environ = {"FENNEC_CORE": str(self.root / "gone"),
                   "FENNEC_SUITE": str(self.root / "gone-suite")}
        with mock.patch.object(launcher.importlib.util, "find_spec", return_value=None), \
             redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(launcher.main([], environ, here), 2)
        message = stderr.getvalue()
        self.assertIn("[fennec-acp]", message)
        self.assertIn("fennec-core was not found", message)
        self.assertIn(str(self.root / "gone-suite" / "fennec-core"), message)


class NoDuplicatedProtocolTests(unittest.TestCase):
    def test_the_launcher_carries_no_acp_or_engine_code(self):
        source = LAUNCHER.read_text(encoding="utf-8")
        for marker in ("jsonrpc", "session/update", "chat/completions", "subprocess.Popen",
                       "HTTPServer", "BaseHTTPRequestHandler"):
            self.assertNotIn(marker, source, f"{marker} belongs in fennec_core.acp")

    @unittest.skipUnless(CORE_ACP.is_file(), "fennec-core is not next to this checkout")
    def test_the_real_agent_owns_the_protocol(self):
        source = CORE_ACP.read_text(encoding="utf-8")
        self.assertIn("def check()", source)
        self.assertIn("session/set_mode", source)


if __name__ == "__main__":
    unittest.main()
