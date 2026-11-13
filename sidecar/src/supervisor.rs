//! The ACP supervisor: what the Python launcher used to do in-process, now as
//! a parent process. It finds fennec-core, hands the process over to
//! `fennec_core.acp` (which owns ACP v1), pumps stdio both ways, and propagates
//! the agent's exit code. A crashing agent takes this process with it - never
//! the IDE, which only ever sees a subprocess exit.

use std::io::{self, Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use std::thread;

use crate::discovery::{self, Environ};
use fennec_client::suite;

/// Mirrors the shim: sys.path.insert(0, root), then run fennec_core.acp as
/// __main__ so its `if __name__ == "__main__"` guard fires and main() sees the
/// forwarded argv. The root travels as an argv element, never inside -c text.
const RUNPY_WITH_ROOT: &str =
    "import sys, runpy; sys.path.insert(0, sys.argv.pop(1)); runpy.run_module('fennec_core.acp', run_name='__main__')";
const RUNPY_INSTALLED: &str =
    "import runpy; runpy.run_module('fennec_core.acp', run_name='__main__')";
/// 0: the agent module is importable; 1: package without the agent; 2: neither.
/// The parent is probed first: find_spec of a submodule raises when the parent
/// package is absent.
const PROBE_INSTALLED: &str =
    "import importlib.util as u; pkg = u.find_spec('fennec_core'); raise SystemExit(2 if pkg is None else (0 if u.find_spec('fennec_core.acp') else 1))";

fn report(message: &str) -> i32 {
    eprintln!("[fennec-acp] {message}");
    2
}

pub fn run(args: &[String], launcher_dir: &Path, environ: &Environ) -> i32 {
    let home = suite::home_in(environ);
    let paths = match discovery::core_search_paths(environ, launcher_dir, &home) {
        Ok(paths) => paths,
        Err(error) => return report(&error.0),
    };
    let python = match discovery::python_for_launcher(environ, &home) {
        Ok(python) => python,
        Err(error) => return report(&error),
    };

    let mut command = Command::new(&python);
    match discovery::find_core(&paths) {
        Some(root) => {
            command.arg("-c").arg(RUNPY_WITH_ROOT).arg(&root);
        }
        None => {
            let probe = Command::new(&python)
                .arg("-c")
                .arg(PROBE_INSTALLED)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
            match probe.map(|s| s.code()) {
                Ok(Some(0)) => {
                    command.arg("-c").arg(RUNPY_INSTALLED);
                }
                Ok(Some(1)) => {
                    return report(&format!(
                        "the fennec_core package on this interpreter cannot run as an ACP agent: \
                         no module named fennec_core.acp. Update fennec-core to {} or point \
                         FENNEC_CORE at a checkout that carries fennec_core/acp.py.",
                        crate::VERSION
                    ));
                }
                _ => return report(&discovery::not_found_message(&paths)),
            }
        }
    }
    command.args(args);
    command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            return report(&format!(
                "cannot start {python}: {error}. Set FENNEC_PYTHON or the python field in the \
                 Fennec configuration to a working interpreter."
            ));
        }
    };

    let mut child_in = child.stdin.take().expect("piped stdin");
    let mut child_out = child.stdout.take().expect("piped stdout");
    let mut child_err = child.stderr.take().expect("piped stderr");

    let out_pump = thread::spawn(move || {
        let mut stdout = io::stdout();
        let _ = io::copy(&mut child_out, &mut stdout);
        let _ = stdout.flush();
    });
    let err_pump = thread::spawn(move || {
        let mut stderr = io::stderr();
        let _ = io::copy(&mut child_err, &mut stderr);
        let _ = stderr.flush();
    });
    // Closing the agent's stdin on parent EOF is the shutdown signal: the ACP
    // loop ends on EOF and shuts its sessions down. If the agent dies first,
    // the broken pipe ends this pump instead.
    thread::spawn(move || {
        let mut stdin = io::stdin();
        let _ = copy_flush(&mut stdin, &mut child_in);
    });

    let status = child.wait();
    let _ = out_pump.join();
    let _ = err_pump.join();
    match status {
        Ok(status) => {
            let code = exit_code(&status);
            if code != 0 {
                eprintln!("[fennec-acp] the ACP agent exited with code {code}");
            }
            code
        }
        Err(error) => report(&format!("could not wait for the ACP agent: {error}")),
    }
}

fn copy_flush(reader: &mut impl Read, writer: &mut impl Write) -> io::Result<u64> {
    let mut buffer = [0u8; 8192];
    let mut total = 0u64;
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            return Ok(total);
        }
        writer.write_all(&buffer[..read])?;
        writer.flush()?;
        total += read as u64;
    }
}

#[cfg(unix)]
fn exit_code(status: &std::process::ExitStatus) -> i32 {
    use std::os::unix::process::ExitStatusExt;
    status.code().unwrap_or_else(|| 128 + status.signal().unwrap_or(0))
}

#[cfg(not(unix))]
fn exit_code(status: &std::process::ExitStatus) -> i32 {
    status.code().unwrap_or(1)
}