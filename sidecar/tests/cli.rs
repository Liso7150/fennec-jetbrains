//! End-to-end tests that run the built sidecar binary: launcher forwarding
//! against a fake fennec-core (ported from air/tests/test_fennec_acp.py) and
//! the stdio JSON-RPC surface with a loopback fake embedding service.
//! No engine, no model, no real network, no IDE.

use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;

use serde_json::{json, Value};

const BIN: &str = env!("CARGO_BIN_EXE_fennec-acp");

static COUNTER: AtomicU64 = AtomicU64::new(0);

struct Temp {
    root: PathBuf,
}

impl Temp {
    fn new(tag: &str) -> Temp {
        let unique = COUNTER.fetch_add(1, Ordering::SeqCst);
        let root = std::env::temp_dir().join(format!(
            "fennec-acp-it-{tag}-{}-{unique}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        Temp { root }
    }

    fn home(&self) -> PathBuf {
        let home = self.root.join("home");
        fs::create_dir_all(&home).unwrap();
        home
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

const FAKE_ACP: &str = r#""""Fake ACP agent entry point; records how the supervisor called it."""
import json
import os
import sys


def main(argv=None):
    trace = os.environ.get("FENNEC_TEST_TRACE")
    if trace:
        with open(trace, "a", encoding="utf-8") as stream:
            stream.write(json.dumps(list(argv if argv is not None else sys.argv[1:])) + "\n")
    mode = os.environ.get("FENNEC_TEST_MODE", "exit7")
    if mode == "echo":
        for line in sys.stdin:
            sys.stdout.write(line)
            sys.stdout.flush()
        return 0
    if mode == "crash":
        sys.stderr.write("fake agent crashed\n")
        sys.stderr.flush()
        return 42
    return 7


if __name__ == "__main__":
    raise SystemExit(main())
"#;

fn make_core(base: &Path) -> PathBuf {
    let package = base.join("fennec_core");
    fs::create_dir_all(&package).unwrap();
    fs::write(package.join("__init__.py"), "__version__ = \"0.0.0-fake\"\n").unwrap();
    fs::write(package.join("acp.py"), FAKE_ACP).unwrap();
    base.to_path_buf()
}

fn base_command(home: &Path) -> Command {
    let mut command = Command::new(BIN);
    for key in ["FENNEC_CORE", "FENNEC_SUITE", "FENNEC_CONFIG", "FENNEC_PYTHON", "FENNEC_EMBED_URL", "FENNEC_TEST_TRACE", "FENNEC_TEST_MODE", "PYTHONPATH"] {
        command.env_remove(key);
    }
    command.env("USERPROFILE", home);
    command.env("HOME", home);
    command.current_dir(home);
    command
}

fn run_with_stdin(command: &mut Command, input: &str) -> (i32, String, String) {
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("sidecar binary spawns");
    child.stdin.take().unwrap().write_all(input.as_bytes()).unwrap();
    let output = child.wait_with_output().unwrap();
    (
        output.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&output.stdout).to_string(),
        String::from_utf8_lossy(&output.stderr).to_string(),
    )
}

fn trace_calls(trace: &Path) -> Vec<Value> {
    if !trace.exists() {
        return Vec::new();
    }
    fs::read_to_string(trace)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

#[test]
fn version_reports_the_sidecar_line() {
    let temp = Temp::new("version");
    let (code, stdout, _) = run_with_stdin(base_command(&temp.home()).arg("--version"), "");
    assert_eq!(code, 0);
    assert_eq!(stdout.trim(), "fennec-acp 1.0.0");
}

#[test]
fn an_unknown_flag_is_rejected_before_any_launch() {
    let temp = Temp::new("unknown");
    let (code, stdout, stderr) = run_with_stdin(
        base_command(&temp.home()).args(["--engine-url", "http://127.0.0.1:9"]),
        "",
    );
    assert_eq!(code, 2);
    assert!(stdout.is_empty());
    assert!(stderr.contains("unrecognized arguments"), "{stderr}");
}

#[test]
fn a_missing_core_exits_two_with_a_readable_reason() {
    let temp = Temp::new("missing");
    let home = temp.home();
    let mut command = base_command(&home);
    command
        .env("FENNEC_CORE", temp.root.join("gone"))
        .env("FENNEC_SUITE", temp.root.join("gone-suite"));
    let (code, _, stderr) = run_with_stdin(&mut command, "");
    assert_eq!(code, 2);
    assert!(stderr.contains("[fennec-acp]"), "{stderr}");
    assert!(stderr.contains("fennec-core was not found"), "{stderr}");
    assert!(
        stderr.contains(&temp.root.join("gone-suite").join("fennec-core").display().to_string()),
        "{stderr}"
    );
}

#[test]
fn check_flag_reaches_the_core_agent_and_propagates_its_code() {
    let temp = Temp::new("check");
    let home = temp.home();
    let core = make_core(&temp.root.join("core"));
    let trace = temp.root.join("trace.jsonl");
    let mut command = base_command(&home);
    command
        .arg("--check")
        .env("FENNEC_CORE", &core)
        .env("FENNEC_TEST_TRACE", &trace);
    let (code, _, stderr) = run_with_stdin(&mut command, "");
    assert_eq!(code, 7, "stderr: {stderr}");
    assert_eq!(trace_calls(&trace), vec![json!(["--check"])]);
}

#[test]
fn no_arguments_starts_the_stdio_agent() {
    let temp = Temp::new("noargs");
    let home = temp.home();
    let core = make_core(&temp.root.join("core"));
    let trace = temp.root.join("trace.jsonl");
    let mut command = base_command(&home);
    command.env("FENNEC_CORE", &core).env("FENNEC_TEST_TRACE", &trace);
    let (code, _, stderr) = run_with_stdin(&mut command, "");
    assert_eq!(code, 7, "stderr: {stderr}");
    assert_eq!(trace_calls(&trace), vec![json!([])]);
}

#[test]
fn the_suite_root_from_the_shared_config_is_searched() {
    let temp = Temp::new("suite");
    let home = temp.home();
    let suite = temp.root.join("configured");
    make_core(&suite.join("fennec-core"));
    let config = home.join(".fennec").join("config.json");
    fs::create_dir_all(config.parent().unwrap()).unwrap();
    fs::write(&config, format!("{{\"suite_root\": {:?}}}", suite.display().to_string())).unwrap();
    let trace = temp.root.join("trace.jsonl");
    let mut command = base_command(&home);
    command.env("FENNEC_TEST_TRACE", &trace);
    let (code, _, stderr) = run_with_stdin(&mut command, "");
    assert_eq!(code, 7, "stderr: {stderr}");
    assert_eq!(trace_calls(&trace), vec![json!([])]);
}

#[test]
fn stdio_is_pumped_both_ways_and_eof_shuts_the_agent_down() {
    let temp = Temp::new("pump");
    let home = temp.home();
    let core = make_core(&temp.root.join("core"));
    let mut command = base_command(&home);
    command
        .env("FENNEC_CORE", &core)
        .env("FENNEC_TEST_MODE", "echo");
    let request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n";
    let (code, stdout, stderr) = run_with_stdin(&mut command, request);
    assert_eq!(code, 0, "stderr: {stderr}");
    // Python's text-mode stdout emits CRLF on Windows; the pump is byte-transparent.
    assert_eq!(stdout.replace("\r\n", "\n"), request);
}

#[test]
fn a_crashing_agent_is_reported_and_its_code_propagates() {
    let temp = Temp::new("crash");
    let home = temp.home();
    let core = make_core(&temp.root.join("core"));
    let mut command = base_command(&home);
    command
        .env("FENNEC_CORE", &core)
        .env("FENNEC_TEST_MODE", "crash");
    let (code, _, stderr) = run_with_stdin(&mut command, "");
    assert_eq!(code, 42);
    assert!(stderr.contains("fake agent crashed"), "{stderr}");
    assert!(stderr.contains("the ACP agent exited with code 42"), "{stderr}");
}

// ---- the JSON-RPC surface ----

fn rpc_command(home: &Path) -> Command {
    let mut command = base_command(home);
    command.arg("--rpc");
    command
}

fn rpc_replies(stdout: &str) -> Vec<Value> {
    stdout
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).expect("each reply is one JSON line"))
        .collect()
}

#[test]
fn rpc_chunks_source_over_stdio() {
    let temp = Temp::new("rpc-chunk");
    let request = json!({
        "jsonrpc": "2.0", "id": 1, "method": "context/chunk",
        "params": {"text": "pub fn alpha() {}\nfn beta() {}\n", "path": "lib.rs"}
    });
    let (code, stdout, stderr) = run_with_stdin(&mut rpc_command(&temp.home()), &format!("{request}\n"));
    assert_eq!(code, 0, "stderr: {stderr}");
    let replies = rpc_replies(&stdout);
    assert_eq!(replies.len(), 1);
    assert_eq!(replies[0]["id"], json!(1));
    let chunks = replies[0]["result"]["chunks"].as_array().unwrap();
    let symbols: Vec<&str> = chunks.iter().map(|c| c["symbol"].as_str().unwrap()).collect();
    assert_eq!(symbols, vec!["alpha", "beta"]);
    assert_eq!(chunks[0]["start_byte"], json!(0));
}

/// One-shot loopback embedding fake: maps known texts to fixed vectors.
fn fake_embed_server() -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = stream.unwrap();
            let mut reader = BufReader::new(&stream);
            let mut length = 0usize;
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                if line.to_ascii_lowercase().starts_with("content-length:") {
                    length = line.split(':').nth(1).unwrap().trim().parse().unwrap();
                }
                if line == "\r\n" {
                    break;
                }
            }
            let mut body = vec![0u8; length];
            reader.read_exact(&mut body).unwrap();
            let request: Value = serde_json::from_slice(&body).unwrap();
            let texts = request["input"].as_array().unwrap();
            let data: Vec<Value> = texts
                .iter()
                .map(|text| {
                    let vector = match text.as_str().unwrap() {
                        "cat" => vec![1.0, 0.0],
                        "dog" => vec![0.0, 1.0],
                        "kitten" => vec![0.9, 0.1],
                        _ => vec![0.0, 0.0],
                    };
                    json!({"embedding": vector})
                })
                .collect();
            let response = json!({"data": data}).to_string();
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                response.len()
            );
            stream.write_all(head.as_bytes()).unwrap();
            stream.write_all(response.as_bytes()).unwrap();
            stream.flush().unwrap();
        }
    });
    (port, handle)
}

#[test]
fn rpc_search_indexes_and_ranks_with_real_cosine_scores() {
    let temp = Temp::new("rpc-search");
    let (port, server) = fake_embed_server();
    let mut command = rpc_command(&temp.home());
    command.env("FENNEC_EMBED_URL", format!("http://127.0.0.1:{port}"));
    let index = json!({
        "jsonrpc": "2.0", "id": 1, "method": "context/search",
        "params": {"documents": [
            {"id": "cat-doc", "text": "cat"},
            {"id": "dog-doc", "text": "dog"}
        ]}
    });
    let search = json!({
        "jsonrpc": "2.0", "id": 2, "method": "context/search",
        "params": {"query": "kitten", "k": 2}
    });
    let input = format!("{index}\n{search}\n");
    let (code, stdout, stderr) = run_with_stdin(&mut command, &input);
    assert_eq!(code, 0, "stderr: {stderr}");
    let replies = rpc_replies(&stdout);
    assert_eq!(replies.len(), 2);
    assert_eq!(replies[0]["result"]["indexed"], json!(2));
    let results = replies[1]["result"]["results"].as_array().unwrap();
    assert_eq!(results.len(), 2);
    assert_eq!(results[0]["id"], json!("cat-doc"));
    let score = results[0]["score"].as_f64().unwrap();
    let expected = 0.9 / (f64::sqrt(0.81 + 0.01) + 1e-9);
    assert!((score - expected).abs() < 1e-6, "score {score} vs {expected}");
    assert!(results[1]["score"].as_f64().unwrap() < score);
    drop(server);
}

#[test]
fn rpc_search_without_the_service_is_an_error_not_invented_scores() {
    let temp = Temp::new("rpc-down");
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let mut command = rpc_command(&temp.home());
    command.env("FENNEC_EMBED_URL", format!("http://127.0.0.1:{port}"));
    let request = json!({
        "jsonrpc": "2.0", "id": 1, "method": "context/search",
        "params": {"query": "anything", "documents": [{"id": "d", "text": "body"}]}
    });
    let (code, stdout, stderr) = run_with_stdin(&mut command, &format!("{request}\n"));
    assert_eq!(code, 0, "stderr: {stderr}");
    let replies = rpc_replies(&stdout);
    assert_eq!(replies.len(), 1);
    assert_eq!(replies[0]["error"]["code"], json!(-32000));
    assert!(replies[0]["error"]["message"].as_str().unwrap().contains("embedding"));
    assert!(replies[0].get("result").is_none());
}

#[test]
fn rpc_reports_parse_errors_and_unknown_methods_and_stays_alive() {
    let temp = Temp::new("rpc-errors");
    let input = "{not json\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}\n{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"context/chunk\",\"params\":{\"text\":\"fn ok() {}\"}}\n";
    let (code, stdout, stderr) = run_with_stdin(&mut rpc_command(&temp.home()), input);
    assert_eq!(code, 0, "stderr: {stderr}");
    let replies = rpc_replies(&stdout);
    assert_eq!(replies.len(), 3);
    assert_eq!(replies[0]["error"]["code"], json!(-32700));
    assert_eq!(replies[1]["error"]["code"], json!(-32601));
    assert_eq!(replies[2]["result"]["chunks"][0]["symbol"], json!("ok"));
}

#[test]
fn rpc_notifications_produce_no_output() {
    let temp = Temp::new("rpc-notif");
    let input = "{\"jsonrpc\":\"2.0\",\"method\":\"context/chunk\",\"params\":{\"text\":\"fn a() {}\"}}\n";
    let (code, stdout, stderr) = run_with_stdin(&mut rpc_command(&temp.home()), input);
    assert_eq!(code, 0, "stderr: {stderr}");
    assert!(stdout.trim().is_empty(), "{stdout}");
}