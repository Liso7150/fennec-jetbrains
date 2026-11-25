//! The ACP supervisor: what the Python launcher used to do in-process, now as
//! a parent process. It finds fennec-core, hands the process over to its
//! `acp.py` (which owns ACP v1), pumps stdio both ways, and propagates
//! the agent's exit code. A crashing agent takes this process with it - never
//! the IDE, which only ever sees a subprocess exit.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;

use crate::controls::{AccessMode, Controls};
use crate::discovery::{self, Environ};
use fennec_client::suite;
use serde_json::{Value, json};

/// Mirrors the shim: sys.path.insert(0, root), then run the checkout's acp.py as
/// __main__ so its `if __name__ == "__main__"` guard fires and main() sees the
/// forwarded argv. The root travels as an argv element, never inside -c text.
/// fennec-core keeps its modules at the checkout root, so this is a file path,
/// not a package path.
const RUNPY_WITH_ROOT: &str = "import os, runpy, sys; root = sys.argv.pop(1); sys.path.insert(0, root); runpy.run_path(os.path.join(root, 'acp.py'), run_name='__main__')";
/// The wheel installs the same modules top-level, so an installed core runs by module name.
const RUNPY_INSTALLED: &str = "import runpy; runpy.run_module('acp', run_name='__main__')";
/// 0: this interpreter can import the agent; anything else: it has no fennec-core.
/// The import is done, not just located, because a module that raises on import
/// is not a core the supervisor can run.
const PROBE_INSTALLED: &str = "import acp";

fn report(message: &str) -> i32 {
    eprintln!("[fennec-acp] {message}");
    2
}

pub fn run(args: &[String], launcher_dir: &Path, environ: &Environ) -> i32 {
    let home = suite::home_in(environ);
    let controls = Controls::new(environ);
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
                _ => return report(&discovery::not_found_message(&paths)),
            }
        }
    }
    command.args(args);
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            return report(&format!(
                "cannot start {python}: {error}. Set FENNEC_PYTHON or the python field in the \
                 Fennec configuration to a working interpreter."
            ));
        }
    };

    let child_in = child.stdin.take().expect("piped stdin");
    let child_out = child.stdout.take().expect("piped stdout");
    let mut child_err = child.stderr.take().expect("piped stderr");
    let child_in: Arc<Mutex<Option<Box<dyn Write + Send>>>> =
        Arc::new(Mutex::new(Some(Box::new(child_in))));

    let output_controls = controls.clone();
    let output_input = child_in.clone();
    let out_pump = thread::spawn(move || {
        let mut stdout = io::stdout();
        for line in BufReader::new(child_out).lines() {
            let Ok(line) = line else { break };
            if let Some(reply) = permission_reply(&line, output_controls.access()) {
                let mut slot = match output_input.lock() {
                    Ok(slot) => slot,
                    Err(_) => break,
                };
                if let Some(input) = slot.as_mut() {
                    if writeln!(input, "{reply}").is_ok() && input.flush().is_ok() {
                        continue;
                    }
                }
            }
            if writeln!(stdout, "{line}").is_err() {
                break;
            }
            let _ = stdout.flush();
        }
    });
    let err_pump = thread::spawn(move || {
        let mut stderr = io::stderr();
        let _ = io::copy(&mut child_err, &mut stderr);
        let _ = stderr.flush();
    });
    // Closing the agent's stdin on parent EOF is the shutdown signal: the ACP
    // loop ends on EOF and shuts its sessions down. If the agent dies first,
    // the broken pipe ends this pump instead.
    let input_copy = child_in.clone();
    thread::spawn(move || {
        let mut stdin = io::stdin();
        let mut buffer = [0u8; 8192];
        loop {
            let read = match stdin.read(&mut buffer) {
                Ok(read) => read,
                Err(_) => break,
            };
            if read == 0 {
                break;
            }
            let mut slot = match input_copy.lock() {
                Ok(slot) => slot,
                Err(_) => return,
            };
            let Some(input) = slot.as_mut() else { return };
            if input.write_all(&buffer[..read]).is_err() || input.flush().is_err() {
                return;
            }
        }
        if let Ok(mut slot) = input_copy.lock() {
            *slot = None;
        }
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

fn permission_reply(line: &str, access: AccessMode) -> Option<String> {
    if !access.auto_approves() {
        return None;
    }
    let value: Value = serde_json::from_str(line).ok()?;
    if value.get("method").and_then(Value::as_str) != Some("session/request_permission") {
        return None;
    }
    let id = value.get("id")?.clone();
    Some(
        serde_json::to_string(&json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": { "outcome": { "outcome": "selected", "optionId": "allow_always" } }
        }))
        .ok()?,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const PERMISSION: &str = r#"{"jsonrpc":"2.0","id":"fennec-1","method":"session/request_permission","params":{"sessionId":"s","toolCall":{"toolName":"write_file"}}}"#;

    #[test]
    fn ask_mode_forwards_permission_requests() {
        assert!(permission_reply(PERMISSION, AccessMode::Ask).is_none());
    }

    #[test]
    fn auto_and_full_answer_permission_requests_without_the_ide() {
        for mode in [AccessMode::Auto, AccessMode::Full] {
            let reply = permission_reply(PERMISSION, mode).expect("auto approval reply");
            let value: Value = serde_json::from_str(&reply).unwrap();
            assert_eq!(value["id"], json!("fennec-1"));
            assert_eq!(
                value["result"]["outcome"]["optionId"],
                json!("allow_always")
            );
        }
    }

    #[test]
    fn unrelated_frames_are_never_intercepted() {
        assert!(
            permission_reply(
                r#"{"jsonrpc":"2.0","id":1,"method":"session/prompt","params":{}}"#,
                AccessMode::Auto
            )
            .is_none()
        );
        assert!(permission_reply("not json", AccessMode::Full).is_none());
    }
}

#[cfg(unix)]
fn exit_code(status: &std::process::ExitStatus) -> i32 {
    use std::os::unix::process::ExitStatusExt;
    status
        .code()
        .unwrap_or_else(|| 128 + status.signal().unwrap_or(0))
}

#[cfg(not(unix))]
fn exit_code(status: &std::process::ExitStatus) -> i32 {
    status.code().unwrap_or(1)
}
