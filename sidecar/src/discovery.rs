//! fennec-core discovery, ported from the Python launcher (`air/fennec-acp.py`),
//! plus the interpreter resolution the plugin's SharedConfig contract defines.
//! The suite, config and path rules themselves belong to `fennec_client::suite`,
//! which the desktop consumes too; only the ACP core lookup and the plugin's own
//! contracts are here. The ACP protocol stays in `fennec_core.acp`; nothing here
//! speaks it.

use std::path::{Component, Path, PathBuf};

use serde_json::{Map, Value};

pub use fennec_client::suite::Environ;
use fennec_client::suite::{self, ConfigRead, Relative};

#[derive(Debug, Clone, PartialEq)]
pub struct CoreNotFound(pub String);

impl std::fmt::Display for CoreNotFound {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for CoreNotFound {}

/// The SharedConfig form of fennec-client's expansion: `~` expands and anything
/// still relative hangs off the home the value was read from, which is what the
/// plugin's Java side does with a relative path.
pub fn expand(value: &str, home: &Path) -> PathBuf {
    suite::expand_in(value, home, Relative::Home)
}

pub fn config_file(environ: &Environ, home: &Path) -> PathBuf {
    suite::config_file_in(environ, home, Relative::Home)
}

/// The plugin's forgiving read: an absent or non-object config is simply no
/// configuration, a config that cannot be read is worth naming.
pub fn shared_config(environ: &Environ, home: &Path) -> Result<Map<String, Value>, CoreNotFound> {
    let location = config_file(environ, home);
    let explicit = suite::env_value(environ, "FENNEC_CONFIG").is_some();
    match suite::read_config(&location) {
        ConfigRead::Object(map) => Ok(map),
        ConfigRead::Absent if explicit => Err(CoreNotFound(format!(
            "configured Fennec config does not exist: {}",
            location.display()
        ))),
        ConfigRead::Absent => Ok(Map::new()),
        ConfigRead::Unreadable(reason) => Err(CoreNotFound(format!(
            "cannot read Fennec config {}: {}",
            location.display(),
            reason
        ))),
        ConfigRead::NotAnObject => Ok(Map::new()),
    }
}

pub fn suite_root(environ: &Environ, config: &Map<String, Value>, home: &Path) -> PathBuf {
    suite::suite_root_in(environ, config, home, Relative::Home)
}

/// Python's `Path.parent` keeps "." at the top; Rust's returns None.
fn py_parent(path: &Path) -> PathBuf {
    match path.parent() {
        Some(parent) if parent.as_os_str().is_empty() => PathBuf::from("."),
        Some(parent) => parent.to_path_buf(),
        None => path.to_path_buf(),
    }
}

/// Directories that could hold the fennec_core package, most specific first.
/// `launcher_dir` is the sidecar executable's directory: `<suite>/fennec-jetbrains/air`
/// in a checkout, so the suite root - and fennec-core next to it - is two levels up.
pub fn core_search_paths(
    environ: &Environ,
    launcher_dir: &Path,
    home: &Path,
) -> Result<Vec<PathBuf>, CoreNotFound> {
    let mut paths = Vec::new();
    if let Some(value) = suite::env_value(environ, "FENNEC_CORE") {
        paths.push(expand(value, home));
    }
    paths.push(suite_root(environ, &shared_config(environ, home)?, home).join("fennec-core"));
    paths.push(py_parent(&py_parent(launcher_dir)).join("fennec-core"));
    Ok(paths)
}

/// acp.py, not just __init__.py: a checkout that predates the ACP agent is the
/// common case of "found", and importing it is a traceback, not a miss.
pub fn find_core(paths: &[PathBuf]) -> Option<PathBuf> {
    paths
        .iter()
        .find(|candidate| candidate.join("fennec_core").join("acp.py").is_file())
        .cloned()
}

pub fn not_found_message(paths: &[PathBuf]) -> String {
    let searched = paths
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(", ");
    format!(
        "fennec-core was not found (looked in: {searched}). Run the Fennec environment \
         installer or set FENNEC_CORE to the checkout that contains fennec_core/."
    )
}

// ---- interpreter resolution (the plugin's SharedConfig contract) ----

pub fn is_windows() -> bool {
    cfg!(target_os = "windows")
}

/// Lexical normalize, like Java's `Path.normalize`: collapse "." and "..".
pub fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !out.pop() {
                    out.push("..");
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

fn resolve(value: &str, home: &Path) -> PathBuf {
    normalize(&expand(value, home))
}

fn executable(candidate: &Path) -> bool {
    if !candidate.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(candidate)
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    }
    #[cfg(not(unix))]
    {
        true
    }
}

/// A bare name is what the config stores; PATH is what makes it an executable.
fn search_path(environ: &Environ, names: &[&str]) -> String {
    if let Some(path) = suite::env_value(environ, "PATH") {
        let separator = if is_windows() { ';' } else { ':' };
        for directory in path.split(separator) {
            if directory.trim().is_empty() {
                continue;
            }
            for name in names {
                let candidate = Path::new(directory).join(name);
                if executable(&candidate) {
                    return normalize(&absolute(&candidate)).display().to_string();
                }
            }
        }
    }
    names[0].to_string()
}

fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
}

/// The same file read fennec-client performs, under the plugin's stricter
/// contract: here a config that is not an object is an error, not an empty one.
fn read_config_object(location: &Path, explicit: bool) -> Result<Map<String, Value>, String> {
    match suite::read_config(location) {
        ConfigRead::Object(map) => Ok(map),
        ConfigRead::Absent if explicit => {
            Err(format!("Fennec configuration does not exist: {}", location.display()))
        }
        ConfigRead::Absent => Ok(Map::new()),
        _ => Err(format!(
            "Cannot read Fennec configuration: {}",
            location.display()
        )),
    }
}

/// The interpreter the sidecar spawns, resolved the way the plugin's SharedConfig
/// resolves it: a blank or bare value is searched on PATH, a path is made absolute,
/// and the answer is never an empty string the supervisor cannot exec.
pub fn python_for_launcher(environ: &Environ, home: &Path) -> Result<String, String> {
    let home = normalize(&absolute(home));
    let configured = suite::env_nonblank(environ, "FENNEC_CONFIG");
    let location = match configured {
        Some(value) => resolve(value, &home),
        None => home.join(".fennec").join("config.json"),
    };
    let data = read_config_object(&location, configured.is_some())?;
    let mut python = suite::env_nonblank(environ, "FENNEC_PYTHON").map(str::to_string);
    if python.is_none() {
        if let Some(value) = data.get("python") {
            if !value.is_null() {
                python = Some(
                    value
                        .as_str()
                        .ok_or_else(|| "Fennec configuration field must be a string: python".to_string())?
                        .to_string(),
                );
            }
        }
    }
    let python = match python {
        None => search_path(
            environ,
            if is_windows() {
                &["python.exe", "python3.exe"]
            } else {
                &["python3", "python"]
            },
        ),
        Some(value) if value.trim().is_empty() => search_path(
            environ,
            if is_windows() {
                &["python.exe", "python3.exe"]
            } else {
                &["python3", "python"]
            },
        ),
        Some(value) if !value.starts_with('~') && !value.contains('/') && !value.contains('\\') => {
            search_path(environ, &[value.as_str()])
        }
        Some(value) => resolve(&value, &home).display().to_string(),
    };
    Ok(python)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    struct TempHome {
        root: PathBuf,
        home: PathBuf,
        here: PathBuf,
    }

    impl TempHome {
        fn new(tag: &str) -> TempHome {
            let root = std::env::temp_dir().join(format!("fennec-sidecar-{tag}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&root);
            let home = root.join("home");
            let here = root.join("suite").join("fennec-jetbrains").join("air");
            fs::create_dir_all(&here).unwrap();
            fs::create_dir_all(&home).unwrap();
            TempHome { root, home, here }
        }

        fn write_config(&self, data: &str) -> PathBuf {
            let path = self.home.join(".fennec").join("config.json");
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, data).unwrap();
            path
        }
    }

    impl Drop for TempHome {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    fn make_core(base: &Path) -> PathBuf {
        let package = base.join("fennec_core");
        fs::create_dir_all(&package).unwrap();
        fs::write(package.join("__init__.py"), "__version__ = \"0.0.0-fake\"\n").unwrap();
        fs::write(package.join("acp.py"), "def main(argv=None):\n    return 7\n").unwrap();
        base.to_path_buf()
    }

    fn environ(pairs: &[(&str, &str)]) -> Environ {
        pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    #[test]
    fn search_order_is_explicit_then_suite_then_sibling() {
        let t = TempHome::new("order");
        let explicit = make_core(&t.root.join("explicit"));
        let suite = make_core(&t.root.join("configured").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", suite.parent().unwrap().display().to_string()));
        let paths = core_search_paths(
            &environ(&[("FENNEC_CORE", &explicit.display().to_string())]),
            &t.here,
            &t.home,
        )
        .unwrap();
        assert_eq!(
            paths,
            vec![
                explicit,
                suite,
                t.here.parent().unwrap().parent().unwrap().join("fennec-core")
            ]
        );
    }

    #[test]
    fn fennec_core_wins_over_the_suite_root() {
        let t = TempHome::new("wins");
        let explicit = make_core(&t.root.join("explicit"));
        let suite = make_core(&t.root.join("configured").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", suite.parent().unwrap().display().to_string()));
        let found = find_core(
            &core_search_paths(
                &environ(&[("FENNEC_CORE", &explicit.display().to_string())]),
                &t.here,
                &t.home,
            )
            .unwrap(),
        );
        assert_eq!(found, Some(explicit));
    }

    #[test]
    fn a_pointless_fennec_core_falls_through_to_the_suite() {
        let t = TempHome::new("fallthrough");
        let empty = t.root.join("empty");
        fs::create_dir_all(&empty).unwrap();
        let suite = make_core(&t.root.join("configured").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", suite.parent().unwrap().display().to_string()));
        let found = find_core(
            &core_search_paths(&environ(&[("FENNEC_CORE", &empty.display().to_string())]), &t.here, &t.home)
                .unwrap(),
        );
        assert_eq!(found, Some(suite));
    }

    #[test]
    fn suite_defaults_to_the_home_and_relative_values_are_home_relative() {
        let t = TempHome::new("suite-default");
        assert_eq!(suite_root(&Environ::new(), &Map::new(), &t.home), t.home.join("Fennec-Suite"));
        let mut config = Map::new();
        config.insert("suite_root".into(), Value::String("My/Suite".into()));
        assert_eq!(suite_root(&Environ::new(), &config, &t.home), t.home.join("My").join("Suite"));
    }

    #[test]
    fn suite_root_comes_from_the_shared_config() {
        let t = TempHome::new("suite-config");
        let suite = make_core(&t.root.join("configured").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", suite.parent().unwrap().display().to_string()));
        let config = shared_config(&Environ::new(), &t.home).unwrap();
        assert_eq!(suite_root(&Environ::new(), &config, &t.home), suite.parent().unwrap());
        assert_eq!(find_core(&core_search_paths(&Environ::new(), &t.here, &t.home).unwrap()), Some(suite));
    }

    #[test]
    fn fennec_suite_environment_overrides_the_shared_config() {
        let t = TempHome::new("suite-env");
        let env_suite = make_core(&t.root.join("env-suite").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", t.root.join("missing").display().to_string()));
        let found = find_core(
            &core_search_paths(
                &environ(&[("FENNEC_SUITE", &env_suite.parent().unwrap().display().to_string())]),
                &t.here,
                &t.home,
            )
            .unwrap(),
        );
        assert_eq!(found, Some(env_suite));
    }

    #[test]
    fn a_checkout_next_to_the_launcher_needs_no_config() {
        let t = TempHome::new("sibling");
        let sibling = make_core(&t.here.parent().unwrap().parent().unwrap().join("fennec-core"));
        assert_eq!(find_core(&core_search_paths(&Environ::new(), &t.here, &t.home).unwrap()), Some(sibling));
    }

    #[test]
    fn a_core_without_the_acp_agent_is_not_a_core() {
        let t = TempHome::new("pre-acp");
        let old = t.root.join("old");
        fs::create_dir_all(old.join("fennec_core")).unwrap();
        fs::write(old.join("fennec_core").join("__init__.py"), "").unwrap();
        let suite = make_core(&t.root.join("configured").join("fennec-core"));
        t.write_config(&format!("{{\"suite_root\": {:?}}}", suite.parent().unwrap().display().to_string()));
        let found = find_core(
            &core_search_paths(&environ(&[("FENNEC_CORE", &old.display().to_string())]), &t.here, &t.home)
                .unwrap(),
        );
        assert_eq!(found, Some(suite));
    }

    #[test]
    fn default_config_location_and_empty_config() {
        let t = TempHome::new("config-default");
        let path = t.write_config("{}");
        assert_eq!(config_file(&Environ::new(), &t.home), path);
        assert!(shared_config(&Environ::new(), &t.home).unwrap().is_empty());
    }

    #[test]
    fn a_config_that_is_not_an_object_is_ignored() {
        let t = TempHome::new("config-array");
        t.write_config("[1, 2]");
        assert!(shared_config(&Environ::new(), &t.home).unwrap().is_empty());
    }

    #[test]
    fn fennec_config_pointing_nowhere_is_an_error() {
        let t = TempHome::new("config-missing");
        let missing = t.home.join("nope.json");
        let error = shared_config(&environ(&[("FENNEC_CONFIG", &missing.display().to_string())]), &t.home)
            .unwrap_err();
        assert!(error.0.contains("nope.json"), "{}", error.0);
    }

    #[test]
    fn broken_json_config_names_the_file() {
        let t = TempHome::new("config-broken");
        let path = t.write_config("{oops");
        let error = shared_config(&environ(&[("FENNEC_CONFIG", &path.display().to_string())]), &t.home)
            .unwrap_err();
        assert!(error.0.contains(&path.display().to_string()), "{}", error.0);
    }

    #[test]
    fn a_broken_config_stops_discovery() {
        let t = TempHome::new("config-stops");
        t.write_config("{oops");
        let result = core_search_paths(
            &environ(&[("FENNEC_CONFIG", &t.home.join(".fennec").join("config.json").display().to_string())]),
            &t.here,
            &t.home,
        );
        assert!(result.is_err());
    }

    #[test]
    fn missing_core_lists_the_directories_it_searched() {
        let t = TempHome::new("not-found");
        let missing = t.root.join("nowhere");
        let dead_suite = t.root.join("no-suite");
        let paths = core_search_paths(
            &environ(&[
                ("FENNEC_CORE", &missing.display().to_string()),
                ("FENNEC_SUITE", &dead_suite.display().to_string()),
            ]),
            &t.here,
            &t.home,
        )
        .unwrap();
        assert!(find_core(&paths).is_none());
        let message = not_found_message(&paths);
        assert!(message.contains(&missing.display().to_string()));
        assert!(message.contains(&dead_suite.join("fennec-core").display().to_string()));
        assert!(message.contains("FENNEC_CORE"));
        assert!(message.contains("installer"));
    }

    #[test]
    fn the_plugin_resolves_configured_paths_against_home() {
        let t = TempHome::new("relative-home");
        let core = make_core(&t.home.join("core"));
        let paths = core_search_paths(&environ(&[("FENNEC_CORE", "core")]), &t.here, &t.home).unwrap();
        assert_eq!(paths[0], core);
        assert_eq!(config_file(&environ(&[("FENNEC_CONFIG", "cfg.json")]), &t.home), t.home.join("cfg.json"));
        assert_eq!(suite_root(&Environ::new(), &Map::new(), &t.home), t.home.join("Fennec-Suite"));
    }

    // ---- python_for_launcher: the SharedConfig contract ----

    #[test]
    fn python_path_values_are_resolved_against_home() {
        let t = TempHome::new("python-path");
        t.write_config("{\"python\": \"tools/py.exe\"}");
        let python = python_for_launcher(&Environ::new(), &t.home).unwrap();
        assert_eq!(python, t.home.join("tools").join("py.exe").display().to_string());
        t.write_config("{\"python\": \"~/py.exe\"}");
        let python = python_for_launcher(&Environ::new(), &t.home).unwrap();
        assert_eq!(python, t.home.join("py.exe").display().to_string());
    }

    #[test]
    fn fennec_python_overrides_the_config() {
        let t = TempHome::new("python-env");
        t.write_config("{\"python\": \"configured.exe\"}");
        let absolute = if is_windows() { "C:\\opt\\py" } else { "/opt/py" };
        let python = python_for_launcher(&environ(&[("FENNEC_PYTHON", absolute)]), &t.home).unwrap();
        assert_eq!(python, Path::new(absolute).display().to_string());
    }

    #[test]
    fn a_bare_python_name_is_searched_on_path() {
        let t = TempHome::new("python-search");
        let bin = t.root.join("bin");
        fs::create_dir_all(&bin).unwrap();
        let name = if is_windows() { "python.exe" } else { "python3" };
        let fake = bin.join(name);
        fs::write(&fake, "#!/bin/sh\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&fake, fs::Permissions::from_mode(0o755)).unwrap();
        }
        let env = environ(&[("PATH", &bin.display().to_string())]);
        let python = python_for_launcher(&env, &t.home).unwrap();
        assert_eq!(python, fake.display().to_string());
        let bare = if is_windows() { "python.exe" } else { "python3" };
        t.write_config(&format!("{{\"python\": {bare:?}}}"));
        let python = python_for_launcher(&env, &t.home).unwrap();
        assert_eq!(python, fake.display().to_string());
    }

    #[test]
    fn a_missing_python_falls_back_to_the_first_name() {
        let t = TempHome::new("python-fallback");
        let env = environ(&[("PATH", &t.root.join("empty-bin").display().to_string())]);
        let python = python_for_launcher(&env, &t.home).unwrap();
        let expected = if is_windows() { "python.exe" } else { "python3" };
        assert_eq!(python, expected);
    }

    #[test]
    fn a_non_string_python_field_is_an_error() {
        let t = TempHome::new("python-type");
        t.write_config("{\"python\": 42}");
        let error = python_for_launcher(&Environ::new(), &t.home).unwrap_err();
        assert_eq!(error, "Fennec configuration field must be a string: python");
    }

    #[test]
    fn a_null_python_field_is_ignored() {
        let t = TempHome::new("python-null");
        t.write_config("{\"python\": null}");
        let env = environ(&[("PATH", &t.root.display().to_string())]);
        assert!(python_for_launcher(&env, &t.home).is_ok());
    }

    #[test]
    fn an_explicit_missing_config_is_an_error_for_python_too() {
        let t = TempHome::new("python-config-missing");
        let error = python_for_launcher(
            &environ(&[("FENNEC_CONFIG", &t.home.join("nope.json").display().to_string())]),
            &t.home,
        )
        .unwrap_err();
        assert!(error.contains("Fennec configuration does not exist"), "{error}");
    }

    #[test]
    fn a_non_object_config_is_an_error_for_python() {
        let t = TempHome::new("python-config-array");
        t.write_config("[1, 2]");
        let error = python_for_launcher(&Environ::new(), &t.home).unwrap_err();
        assert!(error.contains("Cannot read Fennec configuration"), "{error}");
    }

    #[test]
    fn blank_env_values_are_absent() {
        let t = TempHome::new("python-blank");
        t.write_config("{\"python\": \"~/py.exe\"}");
        let python = python_for_launcher(&environ(&[("FENNEC_PYTHON", "   ")]), &t.home).unwrap();
        assert_eq!(python, t.home.join("py.exe").display().to_string());
    }
}