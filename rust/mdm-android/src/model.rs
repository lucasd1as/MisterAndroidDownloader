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
    /// A checksum the finished file must match (hex; MD5, SHA-1, SHA-256, SHA-512 or CRC32).
    #[serde(default)]
    pub checksum: Option<String>,
    /// The file is downloaded straight into the user's chosen folder (`Settings::save_dir`), so
    /// there is nothing to copy when it is finished.
    #[serde(default)]
    pub in_place: bool,
}

/// What the user can change (Settings screen).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Connections one download may open (1..=32).
    pub max_connections: u32,
    /// Downloads running at once (1..=8); the others wait in the queue.
    pub max_active: u32,
    /// Bytes per second for all downloads together; 0 is unlimited.
    pub speed_limit: u64,
    /// A folder to download straight into (no copy afterwards; needs "All files access" on
    /// Android 11+ outside the app's own folders). Empty: the app's folder, then Downloads.
    pub save_dir: String,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            max_connections: 16,
            max_active: 3,
            speed_limit: 0,
            save_dir: String::new(),
        }
    }
}

impl Settings {
    pub fn clamped(mut self) -> Self {
        self.max_connections = self.max_connections.clamp(1, 32);
        self.max_active = self.max_active.clamp(1, 8);
        self.save_dir = self.save_dir.trim().to_owned();
        self
    }
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
