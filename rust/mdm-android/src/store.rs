//! The list and settings on disk: one JSON file, replaced atomically, so a kill in the middle of
//! a save leaves the previous one.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::model::{Item, Settings};

const VERSION: u32 = 1;

#[derive(Default, Serialize, Deserialize)]
pub struct Saved {
    #[serde(default)]
    pub version: u32,
    #[serde(default)]
    pub next_id: u64,
    #[serde(default)]
    pub settings: Settings,
    #[serde(default)]
    pub items: Vec<Item>,
}

fn file(dir: &Path) -> PathBuf {
    dir.join("downloads.json")
}

/// What was saved; a missing or unreadable file is an empty list (the unreadable one is kept
/// beside it as `downloads.json.bad`).
pub fn load(dir: &Path) -> Saved {
    let path = file(dir);
    let bytes = match fs::read(&path) {
        Ok(b) => b,
        Err(_) => return Saved::default(),
    };
    match serde_json::from_slice::<Saved>(&bytes) {
        Ok(s) => s,
        Err(e) => {
            log::error!("{}: {e}; starting with an empty list", path.display());
            let _ = fs::rename(&path, dir.join("downloads.json.bad"));
            Saved::default()
        }
    }
}

pub fn save(dir: &Path, next_id: u64, settings: &Settings, items: &[Item]) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    let saved = serde_json::json!({
        "version": VERSION,
        "next_id": next_id,
        "settings": settings,
        "items": items,
    });
    let tmp = dir.join("downloads.json.tmp");
    fs::write(&tmp, serde_json::to_vec_pretty(&saved)?)?;
    fs::rename(&tmp, file(dir))
}
