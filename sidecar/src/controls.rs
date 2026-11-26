use crate::discovery;
use fennec_client::suite::{self, ConfigRead, Environ, Relative};
use serde_json::{Map, Value, json};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

const ACCESS_FILE: &str = "access.json";
const CONFIG_BACKUP: &str = "config.json.bak-fennec-controls";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccessMode {
    Ask,
    Auto,
    Full,
}

impl AccessMode {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "ask" => Ok(Self::Ask),
            "auto" => Ok(Self::Auto),
            "full" => Ok(Self::Full),
            _ => Err("access must be ask, auto, or full".into()),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ask => "ask",
            Self::Auto => "auto",
            Self::Full => "full",
        }
    }

    pub fn auto_approves(self) -> bool {
        matches!(self, Self::Auto | Self::Full)
    }
}

#[derive(Clone, Debug)]
pub struct Controls {
    pub home: PathBuf,
    pub config_path: PathBuf,
    pub python: String,
    pub suite_root: PathBuf,
}

impl Controls {
    pub fn new(environ: &Environ) -> Self {
        let home = suite::home_in(environ);
        let config_path = suite::config_file_in(environ, &home, Relative::Home);
        let python = discovery::python_for_launcher(environ, &home).unwrap_or_else(|_| {
            if cfg!(windows) {
                "python".into()
            } else {
                "python3".into()
            }
        });
        let suite_root = match suite::read_config(&config_path) {
            ConfigRead::Object(map) => map
                .get("suite_root")
                .and_then(Value::as_str)
                .filter(|value| !value.trim().is_empty())
                .map(|value| suite::expand_in(value, &home, Relative::Home))
                .unwrap_or_else(|| home.join("Fennec-Suite")),
            _ => home.join("Fennec-Suite"),
        };
        Self {
            home,
            config_path,
            python,
            suite_root,
        }
    }

    pub fn at(home: PathBuf, config_path: PathBuf) -> Self {
        let python = if cfg!(windows) {
            "python".into()
        } else {
            "python3".into()
        };
        let suite_root = home.join("Fennec-Suite");
        Self {
            home,
            config_path,
            python,
            suite_root,
        }
    }

    pub fn access_path(&self) -> PathBuf {
        self.home.join(".fennec").join("air").join(ACCESS_FILE)
    }

    fn config(&self) -> Result<Map<String, Value>, String> {
        match suite::read_config(&self.config_path) {
            ConfigRead::Object(map) => Ok(map),
            ConfigRead::Absent => Ok(Map::new()),
            ConfigRead::NotAnObject => Err("Fennec configuration must be a JSON object".into()),
            ConfigRead::Unreadable(error) => {
                Err(format!("Cannot read Fennec configuration: {error}"))
            }
        }
    }

    fn resolve_config_path(&self, value: &str) -> PathBuf {
        suite::expand_in(value, &self.home, Relative::Home)
    }

    fn path_string(path: &Path) -> String {
        let value = path.to_string_lossy().into_owned();
        if cfg!(windows) {
            value.replace('/', "\\")
        } else {
            value
        }
    }

    pub fn models_root(&self) -> Result<PathBuf, String> {
        let value = self
            .config()?
            .get("models_root")
            .and_then(Value::as_str)
            .filter(|value| !value.trim().is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| "fennec-models".into());
        Ok(self.resolve_config_path(&value))
    }

    pub fn model(&self) -> Result<String, String> {
        let config = self.config()?;
        let value = config
            .get("model")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        if value.is_empty() {
            return Ok(String::new());
        }
        let expanded = suite::expand_in(value, &self.home, Relative::Keep);
        let path = if expanded.is_absolute() {
            expanded
        } else {
            self.models_root()?.join(expanded)
        };
        Ok(Self::path_string(&path))
    }

    fn cache_root(&self) -> Result<PathBuf, String> {
        let value = self
            .config()?
            .get("cache_root")
            .and_then(Value::as_str)
            .filter(|value| !value.trim().is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| ".cache/fennec".into());
        Ok(self.resolve_config_path(&value))
    }

    fn core_models(&self, root: &Path) -> Option<Vec<Value>> {
        let core = self.suite_root.join("fennec-core");
        if !core.join("setup_wizard.py").is_file() {
            return None;
        }
        let cache = self.cache_root().ok()?;
        let code = "import json,sys; from setup_wizard import scan_models; print(json.dumps(scan_models(sys.argv[1], cache_root=sys.argv[2])))";
        let output = Command::new(&self.python)
            .current_dir(&core)
            .arg("-c")
            .arg(code)
            .arg(root)
            .arg(cache)
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let rows: Vec<Value> = serde_json::from_slice(&output.stdout).ok()?;
        Some(
            rows.into_iter()
                .filter_map(|row| {
                    let name = row.get("name")?.as_str()?.to_string();
                    let path = if cfg!(windows) {
                        row.get("path")?.as_str()?.replace('/', "\\")
                    } else {
                        row.get("path")?.as_str()?.to_string()
                    };
                    let ready = row.get("ready")?.as_bool()?;
                    let detail = row.get("detail")?.as_str().unwrap_or("").to_string();
                    Some(json!({
                        "name": name,
                        "path": path,
                        "ready": ready,
                        "detail": detail,
                        "classifier": "core",
                    }))
                })
                .collect(),
        )
    }

    pub fn models(&self) -> Result<Vec<Value>, String> {
        let root = self.models_root()?;
        if let Some(models) = self.core_models(&root) {
            return Ok(models);
        }
        let mut models = Vec::new();
        let entries = match fs::read_dir(&root) {
            Ok(entries) => entries,
            Err(_) => return Ok(models),
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            let has_config = path.join("config.json").is_file();
            let has_weights = fs::read_dir(&path)
                .ok()
                .into_iter()
                .flatten()
                .filter_map(Result::ok)
                .any(|item| {
                    item.path().is_file()
                        && matches!(
                            item.path().extension().and_then(|ext| ext.to_str()),
                            Some("safetensors" | "gguf")
                        )
                });
            if !has_config && !has_weights {
                continue;
            }
            let name = entry.file_name().to_string_lossy().into_owned();
            models.push(json!({
                "name": name,
                "path": Self::path_string(&path),
                "ready": false,
                "detail": "core model classifier unavailable",
                "classifier": "fallback",
            }));
        }
        models.sort_by(|left, right| {
            left["name"]
                .as_str()
                .unwrap_or("")
                .to_lowercase()
                .cmp(&right["name"].as_str().unwrap_or("").to_lowercase())
        });
        Ok(models)
    }

    pub fn access(&self) -> AccessMode {
        let Ok(text) = fs::read_to_string(self.access_path()) else {
            return AccessMode::Ask;
        };
        let Ok(value) = serde_json::from_str::<Value>(&text) else {
            return AccessMode::Ask;
        };
        value
            .get("mode")
            .and_then(Value::as_str)
            .and_then(|mode| AccessMode::parse(mode).ok())
            .unwrap_or(AccessMode::Ask)
    }

    pub fn snapshot(&self) -> Result<Value, String> {
        let root = self.models_root()?;
        Ok(json!({
            "access": self.access().as_str(),
            "model": self.model()?,
            "reasoning_effort": self.reasoning_effort()?,
            "models_root": root.to_string_lossy(),
            "python": self.python.clone(),
            "suite_root": self.suite_root.to_string_lossy(),
            "models": self.models()?,
        }))
    }

    pub fn reasoning_effort(&self) -> Result<String, String> {
        let config = self.config()?;
        let value = config
            .get("reasoning_effort")
            .and_then(Value::as_str)
            .unwrap_or("auto");
        match value {
            "auto" | "low" | "medium" | "high" => Ok(value.to_string()),
            _ => Err(format!("Unsupported reasoning effort: {value}")),
        }
    }

    pub fn set(&self, access: AccessMode, model: Option<&str>) -> Result<Value, String> {
        self.set_with_reasoning(access, model, None)
    }

    pub fn set_with_reasoning(
        &self,
        access: AccessMode,
        model: Option<&str>,
        reasoning_effort: Option<&str>,
    ) -> Result<Value, String> {
        let mut config = self.config()?;
        if let Some(level) = reasoning_effort.map(str::trim).filter(|value| !value.is_empty()) {
            match level {
                "auto" | "low" | "medium" | "high" => {
                    config.insert("reasoning_effort".into(), json!(level));
                }
                _ => return Err(format!("Unsupported reasoning effort: {level}")),
            }
        }
        match model.map(str::trim).filter(|value| !value.is_empty()) {
            Some(value) => {
                let root = self.models_root()?;
                let expanded = suite::expand_in(value, &self.home, Relative::Keep);
                let path = if expanded.is_absolute() {
                    expanded
                } else {
                    root.join(expanded)
                };
                if !path.is_dir() {
                    return Err(format!("Model folder does not exist: {}", path.display()));
                }
                if let Ok(models) = self.models() {
                    if let Some(model) = models.iter().find(|model| {
                        model.get("path").and_then(Value::as_str)
                            == Some(Self::path_string(&path).as_str())
                    }) {
                        if model.get("classifier").and_then(Value::as_str) == Some("core")
                            && model.get("ready").and_then(Value::as_bool) != Some(true)
                        {
                            let detail = model
                                .get("detail")
                                .and_then(Value::as_str)
                                .unwrap_or("not servable yet");
                            return Err(format!(
                                "Model {} is not servable yet: {detail}",
                                path.display()
                            ));
                        }
                    }
                }
                config.insert("model".into(), json!(Self::path_string(&path)));
            }
            None => {
                config.insert("model".into(), json!(""));
            }
        }
        write_json_atomic(&self.config_path, Value::Object(config), true)
            .map_err(|error| format!("Cannot write Fennec configuration: {error}"))?;
        write_json_atomic(
            &self.access_path(),
            json!({ "mode": access.as_str() }),
            false,
        )
        .map_err(|error| format!("Cannot write Fennec access mode: {error}"))?;
        self.snapshot()
    }
}

fn write_json_atomic(path: &Path, value: Value, backup: bool) -> std::io::Result<Option<PathBuf>> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let backup_path = if backup && path.exists() {
        let backup = path.with_file_name(CONFIG_BACKUP);
        fs::copy(path, &backup)?;
        Some(backup)
    } else {
        None
    };
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let temporary = path.with_file_name(format!(
        ".{}.{}.{}.tmp",
        path.file_name().unwrap_or_default().to_string_lossy(),
        std::process::id(),
        stamp
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        serde_json::to_writer_pretty(&mut file, &value)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(fs::Permissions::from_mode(0o600))?;
        }
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map(|()| backup_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(label: &str) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("fennec-controls-{label}-{stamp}"))
    }

    #[test]
    fn access_modes_are_explicit_and_safe_by_default() {
        assert_eq!(AccessMode::parse("ask"), Ok(AccessMode::Ask));
        assert!(AccessMode::Auto.auto_approves());
        assert!(AccessMode::Full.auto_approves());
        assert!(!AccessMode::Ask.auto_approves());
        assert!(AccessMode::parse("trust me").is_err());
    }

    #[test]
    fn model_update_preserves_config_and_creates_backup() {
        let home = temp_dir("model");
        let config = home.join(".fennec").join("config.json");
        fs::create_dir_all(config.parent().unwrap()).unwrap();
        fs::write(&config, b"{\"backend\":\"vulkan\",\"custom\":7}\n").unwrap();
        let model = home.join("fennec-models").join("qwen");
        fs::create_dir_all(&model).unwrap();
        fs::write(model.join("config.json"), b"{}").unwrap();
        let controls = Controls::at(home.clone(), config.clone());
        let snapshot = controls
            .set(AccessMode::Auto, Some(model.to_str().unwrap()))
            .unwrap();
        assert_eq!(snapshot["access"], json!("auto"));
        assert_eq!(snapshot["model"], json!(model.to_string_lossy()));
        let written: Value = serde_json::from_slice(&fs::read(&config).unwrap()).unwrap();
        assert_eq!(written["backend"], json!("vulkan"));
        assert_eq!(written["custom"], json!(7));
        assert!(config.with_file_name(CONFIG_BACKUP).is_file());
        assert_eq!(controls.access(), AccessMode::Auto);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn reasoning_effort_round_trips_and_rejects_unknown_levels() {
        let home = temp_dir("reasoning");
        let config = home.join(".fennec").join("config.json");
        fs::create_dir_all(config.parent().unwrap()).unwrap();
        fs::write(&config, b"{\"backend\":\"vulkan\"}\n").unwrap();
        let controls = Controls::at(home.clone(), config.clone());
        let snapshot = controls
            .set_with_reasoning(AccessMode::Ask, None, Some("high"))
            .unwrap();
        assert_eq!(snapshot["reasoning_effort"], json!("high"));
        assert_eq!(controls.reasoning_effort().unwrap(), "high");
        assert!(controls
            .set_with_reasoning(AccessMode::Ask, None, Some("sideways"))
            .is_err());
        let written: Value = serde_json::from_slice(&fs::read(&config).unwrap()).unwrap();
        assert_eq!(written["reasoning_effort"], json!("high"));
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn malformed_config_is_not_overwritten() {
        let home = temp_dir("malformed");
        let config = home.join(".fennec").join("config.json");
        fs::create_dir_all(config.parent().unwrap()).unwrap();
        fs::write(&config, b"not json").unwrap();
        let controls = Controls::at(home.clone(), config.clone());
        assert!(controls.set(AccessMode::Ask, None).is_err());
        assert_eq!(fs::read(&config).unwrap(), b"not json");
        fs::remove_dir_all(home).unwrap();
    }
}
