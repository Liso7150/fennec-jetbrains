//! fennec-acp: the Fennec JetBrains sidecar.
//!
//! Default mode supervises the ACP agent implemented by fennec_core.acp (the
//! protocol stays in Python). `--rpc` serves the plugin's context JSON-RPC
//! surface instead. `--check` and `--version` behave like the Python launcher
//! they replace.

use std::io::{self, BufReader, Write};
use std::path::{Path, PathBuf};

use fennec_sidecar::discovery::Environ;
use fennec_sidecar::{embed, rpc, supervisor, VERSION};

const USAGE: &str = "usage: fennec-acp [--check] [--rpc] [--version]

Launch the Fennec ACP agent implemented by fennec-core, or serve the plugin's
context JSON-RPC surface.

options:
  --check    print the capabilities the agent advertises, then exit
  --rpc      serve context/chunk and context/search over stdio (NDJSON)
  --version  print the sidecar version and exit
  -h, --help show this message and exit

The ACP protocol itself lives in fennec_core.acp.";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let environ: Environ = std::env::vars().collect();
    let launcher_dir = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."));
    let code = run_mode(&args, &launcher_dir, &environ);
    std::process::exit(code);
}

fn run_mode(args: &[String], launcher_dir: &Path, environ: &Environ) -> i32 {
    match args {
        [] => supervisor::run(&[], launcher_dir, environ),
        [flag] if flag == "--check" => supervisor::run(&["--check".to_string()], launcher_dir, environ),
        [flag] if flag == "--version" => {
            println!("fennec-acp {VERSION}");
            0
        }
        [flag] if flag == "--help" || flag == "-h" => {
            println!("{USAGE}");
            0
        }
        [flag] if flag == "--rpc" => {
            let mut state = rpc::State::new(embed::embed_url(environ));
            let stdin = io::stdin();
            let mut stdout = io::stdout();
            match rpc::serve(BufReader::new(stdin.lock()), &mut stdout, &mut state) {
                Ok(()) => 0,
                Err(error) => {
                    eprintln!("[fennec-acp] rpc stdio failed: {error}");
                    1
                }
            }
        }
        _ => {
            let mut stderr = io::stderr();
            let _ = writeln!(stderr, "unrecognized arguments: {}", args.join(" "));
            let _ = writeln!(stderr, "{USAGE}");
            2
        }
    }
}