//! The download list's data: what the UI shows and what is saved between runs.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    /// Waiting for a free slot (`Settings::max_active`).
    Queued,
    Downloading,
    Paused,
    Complete,
    Failed,
}

/// One download in the list.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Item {
    pub id: u64,
    pub url: String,
    /// What the list shows: the name from the address until the engine has the file's own.
    pub name: String,
    /// The file in the app's own folder, once the engine has chosen it.
    pub path: Option<PathBuf>,
    pub status: Status,
    pub size: Option<u64>,
    pub downloaded: u64,
    /// Bytes per second; only meaningful while downloading.
    #[serde(default)]
    pub speed: u64,
    /// Connections receiving right now.
    #[serde(default)]
    pub connections: u32,
    pub error: Option<String>,
    #[serde(default)]
    pub referer: Option<String>,
    /// Unix time, milliseconds.
    pub added_at: u64,
    #[serde(default)]
    pub finished_at: Option<u64>,
    /// Where the finished file went in the device's public Downloads (a content URI), set by the
    /// app after it copied the file there; `path` is then gone.
    #[serde(default)]
    pub exported: Option<String>,
}

/// What the user can change (Settings screen).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Connections one download may open (1..=16).
    pub max_connections: u32,
    /// Downloads running at once (1..=8); the others wait in the queue.
    pub max_active: u32,
    /// Bytes per second for all downloads together; 0 is unlimited.
    pub speed_limit: u64,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            max_connections: 8,
            max_active: 3,
            speed_limit: 0,
        }
    }
}

impl Settings {
    pub fn clamped(mut self) -> Self {
        self.max_connections = self.max_connections.clamp(1, 16);
        self.max_active = self.max_active.clamp(1, 8);
        self
    }
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
