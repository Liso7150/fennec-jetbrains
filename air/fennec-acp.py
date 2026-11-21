#!/usr/bin/env python3
"""Thin ACP launcher for JetBrains Air.

Air starts this file as the ACP agent. It owns no protocol code: it finds
fennec-core on this machine and hands the process over to ``acp.py`` at that
checkout's root, which implements ACP v1 over stdio, the lazy engine start and
the browser / coworker outlets. Stdlib only.
"""
from __future__ import annotations

import argparse
import importlib
import importlib.util
import json
import os
import sys
from pathlib import Path

VERSION = "1.0.0"
AGENT = "acp"
# What says "this directory is fennec-core" is that it carries all of these at
# its root. One name is not enough: the core's modules are top-level files now,
# and a stray `acp.py` in some other project would otherwise be chosen and raise
# a traceback where the user should see "fennec-core was not found". The Rust
# launcher keeps the same list as `fennec-cli`'s CORE_MODULES.
CORE_MODULES = ("acp", "config", "outlets", "setup_wizard")


class CoreNotFound(RuntimeError):
    pass


def _expand(value: str, home: Path) -> Path:
    path = Path(os.path.expanduser(str(value)))
    return path if path.is_absolute() else home / path


def config_file(environ: dict, home: Path) -> Path:
    configured = environ.get("FENNEC_CONFIG")
    return _expand(configured, home) if configured else home / ".fennec" / "config.json"


def shared_config(environ: dict, home: Path) -> dict:
    path = config_file(environ, home)
    if not path.is_file():
        if environ.get("FENNEC_CONFIG"):
            raise CoreNotFound(f"configured Fennec config does not exist: {path}")
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise CoreNotFound(f"cannot read Fennec config {path}: {exc}") from exc
    return data if isinstance(data, dict) else {}


def suite_root(environ: dict, config: dict, home: Path) -> Path:
    configured = environ.get("FENNEC_SUITE") or config.get("suite_root")
    return _expand(configured, home) if configured else home / "Fennec-Suite"


def core_search_paths(environ: dict, launcher_dir: Path, home: Path) -> list[Path]:
    """Directories that could hold the core's modules, most specific first.

    launcher_dir is this file's directory: <suite>/fennec-jetbrains/air in a
    checkout, so the suite root - and fennec-core next to it - is two levels up.
    """
    paths = []
    if environ.get("FENNEC_CORE"):
        paths.append(_expand(environ["FENNEC_CORE"], home))
    paths.append(suite_root(environ, shared_config(environ, home), home) / "fennec-core")
    paths.append(launcher_dir.parent.parent / "fennec-core")
    return paths


def is_core(candidate: Path) -> bool:
    return all((candidate / f"{name}.py").is_file() for name in CORE_MODULES)


def find_core(environ: dict, launcher_dir: Path, home: Path) -> Path | None:
    for candidate in core_search_paths(environ, launcher_dir, home):
        # The whole module set, not one file: a checkout that predates the ACP
        # agent is the common case of "found", and importing it is a traceback,
        # not a miss.
        if is_core(candidate):
            return candidate
    return None


def import_agent(environ: dict, launcher_dir: Path, home: Path | None = None):
    home = Path(home) if home else Path.home()
    root = find_core(environ, launcher_dir, home)
    if root is None:
        if importlib.util.find_spec(AGENT) is None:
            searched = ", ".join(str(p) for p in core_search_paths(environ, launcher_dir, home))
            raise CoreNotFound(
                f"fennec-core was not found (looked in: {searched}). Run the Fennec "
                "environment installer or set FENNEC_CORE to the checkout that "
                "carries acp.py, config.py, outlets.py and setup_wizard.py."
            )
    else:
        text = str(root)
        if text not in sys.path:
            sys.path.insert(0, text)
    return importlib.import_module(AGENT)


def run(argv: list[str] | None = None, environ: dict | None = None,
        launcher_dir: Path | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="fennec-acp",
        description="Launch the Fennec ACP agent implemented by fennec-core.",
        epilog=f"fennec-acp {VERSION}; the ACP protocol itself lives in the core's acp.py.",
    )
    parser.add_argument("--check", action="store_true",
                        help="print the capabilities the agent advertises, then exit")
    parser.add_argument("--version", action="version", version=f"fennec-acp {VERSION}")
    args = parser.parse_args(sys.argv[1:] if argv is None else list(argv))
    agent = import_agent(os.environ if environ is None else environ,
                         launcher_dir or Path(__file__).resolve().parent)
    return agent.main(["--check"] if args.check else [])


def main(argv: list[str] | None = None, environ: dict | None = None,
         launcher_dir: Path | None = None) -> int:
    try:
        return run(argv, environ, launcher_dir)
    except CoreNotFound as error:
        print(f"[fennec-acp] {error}", file=sys.stderr, flush=True)
        return 2
    except ImportError as error:
        # Air shows nothing but "agent exited"; a traceback is what the user reads.
        print(f"[fennec-acp] the Fennec core on this interpreter cannot run as "
              f"an ACP agent: {error}. Update fennec-core to {VERSION} or point "
              "FENNEC_CORE at a checkout that carries acp.py at its root.",
              file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
