//! Mister Download Manager for Android: the download engine of
//! <https://github.com/anefzaoui/misterdownloadmanager> (`mdm-engine`) behind a small queue,
//! a JSON API (`rpc`) and the JNI functions the app's `Native` class loads.

mod jni_bridge;
pub mod manager;
pub mod model;
pub mod rpc;
mod store;

use std::path::PathBuf;
use std::sync::OnceLock;

use serde::Deserialize;
use serde_json::json;

pub use manager::{Config, Manager};

static MANAGER: OnceLock<Manager> = OnceLock::new();

#[derive(Deserialize)]
struct InitConfig {
    data_dir: PathBuf,
    download_dir: PathBuf,
    #[serde(default)]
    user_agent: Option<String>,
}

/// Start the download manager (once per process; later calls only say it is running).
/// `config` is JSON: `data_dir`, `download_dir`, optional `user_agent`.
pub fn init(config: &str) -> String {
    if MANAGER.get().is_some() {
        return json!({ "ok": true, "result": "running" }).to_string();
    }
    let cfg: InitConfig = match serde_json::from_str(config) {
        Ok(c) => c,
        Err(e) => return json!({ "ok": false, "error": format!("bad config: {e}") }).to_string(),
    };
    let user_agent = cfg
        .user_agent
        .filter(|u| !u.trim().is_empty())
        .unwrap_or_else(|| format!("MisterDownloadManager/{} (Android)", env!("CARGO_PKG_VERSION")));
    match Manager::new(Config {
        data_dir: cfg.data_dir,
        download_dir: cfg.download_dir,
        user_agent,
    }) {
        Ok(m) => {
            // Lost a race with another thread's init: theirs stays, ours is dropped.
            let _ = MANAGER.set(m);
            json!({ "ok": true, "result": "started" }).to_string()
        }
        Err(e) => json!({ "ok": false, "error": e }).to_string(),
    }
}

/// `rpc::call` on the process's manager.
pub fn call(method: &str, args: &str) -> String {
    match MANAGER.get() {
        Some(m) => rpc::call(m, method, args),
        None => json!({ "ok": false, "error": "not initialised" }).to_string(),
    }
}
