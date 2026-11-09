//! The download list: a queue over `mdm-engine` downloads.
//!
//! Each running download has a supervisor task that polls the engine's snapshot twice a second
//! and copies it into the list. Pausing cancels the engine download (it saves its state, so a
//! resume continues from the segment table); a download that was running when the app died is
//! queued again at the next start.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use http::{HeaderMap, HeaderValue, header::REFERER};
use mdm_engine::download::{ConnStatus, State as DlState};
use mdm_engine::hosts::HostLimits;
use mdm_engine::limiter::Limiter;
use mdm_engine::{Destination, Download, Options, Outcome, Request, Snapshot, naming, state};
use serde::Serialize;
use tokio::runtime::{Handle, Runtime};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use url::Url;

use crate::model::{Item, Settings, Status, now_ms};
use crate::store;

/// Where things live and who we say we are.
#[derive(Clone, Debug)]
pub struct Config {
    /// The list and settings (the app's private files folder).
    pub data_dir: PathBuf,
    /// Where downloads are written until they are finished.
    pub download_dir: PathBuf,
    /// The User-Agent sent to servers (the device's browser's).
    pub user_agent: String,
}

/// What `list` returns.
#[derive(Serialize)]
pub struct Listing {
    pub items: Vec<Item>,
    pub settings: Settings,
    /// Bytes per second, all downloads together.
    pub speed: u64,
    /// Downloads queued or running: the app keeps its foreground service while this isn't 0.
    pub active: u32,
}

/// What `probe` returns.
#[derive(Serialize)]
pub struct Probed {
    pub name: String,
    pub size: Option<u64>,
    pub url: String,
}

struct Run {
    stop: CancellationToken,
    handle: JoinHandle<()>,
}

struct Entry {
    item: Item,
    /// The supervisor, until it has seen the download end (which can come after the user paused:
    /// the slot stays taken until then).
    run: Option<Run>,
}

struct State {
    items: Vec<Entry>,
    next_id: u64,
    settings: Settings,
    last_save: Instant,
}

struct Inner {
    cfg: Config,
    rt: Handle,
    state: Mutex<State>,
    global: Arc<Limiter>,
    hosts: Arc<HostLimits>,
}

pub struct Manager {
    inner: Arc<Inner>,
    _runtime: Runtime,
}

/// How a download ended.
enum End {
    Done(Outcome),
    Cancelled,
    Failed(String),
}

struct Launch {
    id: u64,
    url: String,
    path: Option<PathBuf>,
    referer: Option<String>,
    start_bytes: u64,
    /// Where a download without a file name yet goes.
    dir: PathBuf,
    checksum: Option<String>,
}

fn lock(m: &Mutex<State>) -> MutexGuard<'_, State> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Manager {
    pub fn new(cfg: Config) -> Result<Manager, String> {
        std::fs::create_dir_all(&cfg.data_dir).map_err(|e| format!("{}: {e}", cfg.data_dir.display()))?;
        std::fs::create_dir_all(&cfg.download_dir)
            .map_err(|e| format!("{}: {e}", cfg.download_dir.display()))?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("mdm")
            .enable_all()
            .build()
            .map_err(|e| format!("runtime: {e}"))?;

        let saved = store::load(&cfg.data_dir);
        let settings = saved.settings.clone().clamped();
        let mut next_id = saved.next_id;
        let items: Vec<Entry> = saved
            .items
            .into_iter()
            .map(|mut item| {
                next_id = next_id.max(item.id + 1);
                item.speed = 0;
                item.connections = 0;
                // Running when the app stopped: continue.
                if item.status == Status::Downloading {
                    item.status = Status::Queued;
                }
                Entry { item, run: None }
            })
            .collect();

        let inner = Arc::new(Inner {
            cfg,
            rt: runtime.handle().clone(),
            state: Mutex::new(State {
                items,
                next_id,
                settings: settings.clone(),
                last_save: Instant::now(),
            }),
            global: Arc::new(Limiter::new(limit(&settings))),
            hosts: Arc::new(HostLimits::default()),
        });
        inner.pump();
        Ok(Manager {
            inner,
            _runtime: runtime,
        })
    }

    /// Add a download and queue it. An address already in the list and not finished is not added
    /// twice: its id comes back (a link shared twice).
    pub fn add(
        &self,
        url: &str,
        referer: Option<String>,
        checksum: Option<String>,
    ) -> Result<u64, String> {
        let url = url.trim();
        let checksum = checksum.map(|c| c.trim().to_owned()).filter(|c| !c.is_empty());
        if let Some(c) = &checksum
            && mdm_engine::verify::Hash::parse(c).is_none()
        {
            return Err("not a checksum (hex MD5, SHA-1, SHA-256, SHA-512 or CRC32)".into());
        }
        let parsed = Url::parse(url).map_err(|e| format!("not a valid address: {e}"))?;
        if !mdm_engine::supported(&parsed) {
            return Err("only http, https, ftp and ftps addresses can be downloaded".into());
        }
        let id = {
            let mut st = lock(&self.inner.state);
            if let Some(e) = st
                .items
                .iter()
                .find(|e| e.item.url == url && e.item.status != Status::Complete)
            {
                let id = e.item.id;
                // A paused or failed one is what the user wants running now.
                drop(st);
                self.resume(id);
                return Ok(id);
            }
            let id = st.next_id;
            st.next_id += 1;
            let name = naming::file_name(None, &parsed);
            st.items.push(Entry {
                item: Item {
                    id,
                    url: url.to_owned(),
                    name: if name.is_empty() { "download".into() } else { name },
                    path: None,
                    status: Status::Queued,
                    size: None,
                    downloaded: 0,
                    speed: 0,
                    connections: 0,
                    error: None,
                    referer: referer.filter(|r| !r.trim().is_empty()),
                    added_at: now_ms(),
                    finished_at: None,
                    exported: None,
                    checksum,
                    in_place: false,
                },
                run: None,
            });
            self.inner.save(&mut st, true);
            id
        };
        self.inner.pump();
        Ok(id)
    }

    pub fn list(&self) -> Listing {
        let st = lock(&self.inner.state);
        let items: Vec<Item> = st.items.iter().map(|e| e.item.clone()).collect();
        Listing {
            speed: items
                .iter()
                .filter(|i| i.status == Status::Downloading)
                .map(|i| i.speed)
                .sum(),
            active: items
                .iter()
                .filter(|i| matches!(i.status, Status::Queued | Status::Downloading))
                .count() as u32,
            settings: st.settings.clone(),
            items,
        }
    }

    /// Stop a download, keeping what is downloaded.
    pub fn pause(&self, id: u64) {
        {
            let mut st = lock(&self.inner.state);
            if let Some(e) = st.items.iter_mut().find(|e| e.item.id == id)
                && matches!(e.item.status, Status::Queued | Status::Downloading)
            {
                e.item.status = Status::Paused;
                e.item.speed = 0;
                if let Some(run) = &e.run {
                    run.stop.cancel();
                }
            }
            self.inner.save(&mut st, true);
        }
        self.inner.pump();
    }

    /// Continue a paused or failed download (queued: it starts when a slot is free).
    pub fn resume(&self, id: u64) {
        {
            let mut st = lock(&self.inner.state);
            if let Some(e) = st.items.iter_mut().find(|e| e.item.id == id)
                && matches!(e.item.status, Status::Paused | Status::Failed)
            {
                e.item.status = Status::Queued;
                e.item.error = None;
            }
            self.inner.save(&mut st, true);
        }
        self.inner.pump();
    }

    pub fn pause_all(&self) {
        let ids: Vec<u64> = lock(&self.inner.state)
            .items
            .iter()
            .filter(|e| matches!(e.item.status, Status::Queued | Status::Downloading))
            .map(|e| e.item.id)
            .collect();
        for id in ids {
            self.pause(id);
        }
    }

    pub fn resume_all(&self) {
        let ids: Vec<u64> = lock(&self.inner.state)
            .items
            .iter()
            .filter(|e| matches!(e.item.status, Status::Paused | Status::Failed))
            .map(|e| e.item.id)
            .collect();
        for id in ids {
            self.resume(id);
        }
    }

    /// Take a download off the list. What it left half-downloaded always goes; its finished file
    /// in the app's folder goes with `delete_files`.
    pub fn remove(&self, id: u64, delete_files: bool) {
        let (path, complete, run) = {
            let mut st = lock(&self.inner.state);
            let Some(pos) = st.items.iter().position(|e| e.item.id == id) else {
                return;
            };
            let mut e = st.items.remove(pos);
            self.inner.save(&mut st, true);
            (
                e.item.path.take(),
                e.item.status == Status::Complete,
                e.run.take(),
            )
        };
        let cleanup = move || {
            if let Some(p) = path {
                remove_leftovers(&p);
                if delete_files || !complete {
                    let _ = std::fs::remove_file(&p);
                }
            }
        };
        match run {
            // The engine still holds the files until its download has stopped.
            Some(run) => {
                run.stop.cancel();
                self.inner.rt.spawn(async move {
                    let _ = run.handle.await;
                    cleanup();
                });
            }
            None => cleanup(),
        }
        self.inner.pump();
    }

    /// Drop the finished downloads from the list (their files stay).
    pub fn clear_completed(&self) -> u32 {
        let mut st = lock(&self.inner.state);
        let before = st.items.len();
        st.items.retain(|e| e.item.status != Status::Complete);
        let n = (before - st.items.len()) as u32;
        self.inner.save(&mut st, true);
        n
    }

    /// The app copied a finished file into the device's public Downloads: remember where, and
    /// free the private copy.
    pub fn set_exported(&self, id: u64, uri: String) -> Result<(), String> {
        let path = {
            let mut st = lock(&self.inner.state);
            let e = st
                .items
                .iter_mut()
                .find(|e| e.item.id == id)
                .ok_or("no such download")?;
            e.item.exported = Some(uri);
            let p = e.item.path.take();
            self.inner.save(&mut st, true);
            p
        };
        if let Some(p) = path {
            remove_leftovers(&p);
            let _ = std::fs::remove_file(p);
        }
        Ok(())
    }

    pub fn settings(&self) -> Settings {
        lock(&self.inner.state).settings.clone()
    }

    pub fn set_settings(&self, settings: Settings) -> Result<(), String> {
        let settings = settings.clamped();
        if !settings.save_dir.is_empty() {
            let dir = Path::new(&settings.save_dir);
            std::fs::create_dir_all(dir)
                .and_then(|_| tempfile_probe(dir))
                .map_err(|e| format!("cannot write to {}: {e}", dir.display()))?;
        }
        {
            let mut st = lock(&self.inner.state);
            self.inner.global.set(limit(&settings));
            st.settings = settings;
            self.inner.save(&mut st, true);
        }
        // More slots may be free now.
        self.inner.pump();
        Ok(())
    }

    /// What the server says about an address (name and size), for the add dialog. Blocks: call
    /// it from a background thread.
    pub fn probe(&self, url: &str, referer: Option<String>) -> Result<Probed, String> {
        let parsed = Url::parse(url.trim()).map_err(|e| format!("not a valid address: {e}"))?;
        if !mdm_engine::supported(&parsed) {
            return Err("only http, https, ftp and ftps addresses can be downloaded".into());
        }
        let settings = lock(&self.inner.state).settings.clone();
        let opts = self.inner.options(&settings);
        let headers = headers(referer.as_deref());
        let info = self.inner.rt.block_on(async {
            tokio::time::timeout(
                Duration::from_secs(20),
                mdm_engine::probe(parsed, headers, &opts),
            )
            .await
        });
        match info {
            Ok(Ok(i)) => Ok(Probed {
                name: i.file_name,
                size: i.size,
                url: i.final_url.to_string(),
            }),
            Ok(Err(e)) => Err(e.to_string()),
            Err(_) => Err("the server did not answer in time".into()),
        }
    }
}

/// Whether a file can be created in `dir`.
fn tempfile_probe(dir: &Path) -> std::io::Result<()> {
    let probe = dir.join(".mdm-write-test");
    std::fs::write(&probe, b"")?;
    std::fs::remove_file(probe)
}

fn limit(s: &Settings) -> Option<u64> {
    (s.speed_limit > 0).then_some(s.speed_limit)
}

fn headers(referer: Option<&str>) -> HeaderMap {
    let mut h = HeaderMap::new();
    if let Some(v) = referer.and_then(|r| HeaderValue::from_str(r).ok()) {
        h.insert(REFERER, v);
    }
    h
}

/// The part and state files beside a download.
fn remove_leftovers(dest: &Path) {
    let _ = std::fs::remove_file(naming::part_path(dest));
    let _ = std::fs::remove_file(state::state_path(dest));
}

impl Inner {
    fn options(&self, settings: &Settings) -> Options {
        Options {
            max_connections: settings.max_connections,
            user_agent: self.cfg.user_agent.clone(),
            // A server that refuses the browser's User-Agent gets our own.
            agent_fallback: Some(format!(
                "MisterDownloadManager/{} (Android)",
                env!("CARGO_PKG_VERSION")
            )),
            global: Some(self.global.clone()),
            host_limits: Some(self.hosts.clone()),
            ..Options::default()
        }
    }

    fn save(&self, st: &mut State, force: bool) {
        if !force && st.last_save.elapsed() < Duration::from_secs(5) {
            return;
        }
        st.last_save = Instant::now();
        let items: Vec<Item> = st.items.iter().map(|e| e.item.clone()).collect();
        if let Err(e) = store::save(&self.cfg.data_dir, st.next_id, &st.settings, &items) {
            log::error!("saving the list: {e}");
        }
    }

    /// Start queued downloads while there are free slots.
    fn pump(self: &Arc<Self>) {
        let mut st = lock(&self.state);
        loop {
            let running = st.items.iter().filter(|e| e.run.is_some()).count() as u32;
            if running >= st.settings.max_active {
                break;
            }
            let Some(idx) = st
                .items
                .iter()
                .position(|e| e.item.status == Status::Queued && e.run.is_none())
            else {
                break;
            };
            self.start(&mut st, idx);
        }
    }

    fn start(self: &Arc<Self>, st: &mut State, idx: usize) {
        let opts = self.options(&st.settings);
        let custom = (!st.settings.save_dir.is_empty()).then(|| PathBuf::from(&st.settings.save_dir));
        let e = &mut st.items[idx];
        // The folder is chosen when a download first starts; a resume goes on in its own.
        if e.item.path.is_none() {
            e.item.in_place = custom.is_some();
        }
        e.item.status = Status::Downloading;
        e.item.error = None;
        e.item.speed = 0;
        let launch = Launch {
            id: e.item.id,
            url: e.item.url.clone(),
            path: e.item.path.clone(),
            referer: e.item.referer.clone(),
            start_bytes: e.item.downloaded,
            dir: custom.unwrap_or_else(|| self.cfg.download_dir.clone()),
            checksum: e.item.checksum.clone(),
        };
        let id = launch.id;
        let stop = CancellationToken::new();
        let work = self
            .rt
            .spawn(supervise(self.clone(), launch, opts, stop.clone()));
        let inner = self.clone();
        // The watcher turns a panic inside the engine into a failed download, not a stuck one.
        let handle = self.rt.spawn(async move {
            let end = match work.await {
                Ok(end) => end,
                Err(e) => End::Failed(format!("internal error: {e}")),
            };
            inner.finish(id, end);
        });
        e.run = Some(Run { stop, handle });
    }

    fn on_progress(&self, id: u64, snap: &Snapshot, speed: u64) {
        let mut st = lock(&self.state);
        let Some(e) = st.items.iter_mut().find(|e| e.item.id == id) else {
            return;
        };
        let it = &mut e.item;
        if let Some(p) = &snap.file
            && it.path.as_ref() != Some(p)
        {
            if let Some(n) = p.file_name().and_then(|n| n.to_str()) {
                it.name = n.to_owned();
            }
            it.path = Some(p.clone());
        }
        if snap.size.is_some() {
            it.size = snap.size;
        }
        it.downloaded = snap.downloaded;
        it.connections = snap
            .connections
            .iter()
            .filter(|c| matches!(c.status, ConnStatus::Receiving))
            .count() as u32;
        it.speed = if snap.state == DlState::Downloading { speed } else { 0 };
        self.save(&mut st, false);
    }

    fn finish(self: &Arc<Self>, id: u64, end: End) {
        {
            let mut st = lock(&self.state);
            if let Some(e) = st.items.iter_mut().find(|e| e.item.id == id) {
                e.run = None;
                let it = &mut e.item;
                it.speed = 0;
                it.connections = 0;
                match end {
                    End::Done(o) => {
                        if let Some(n) = o.path.file_name().and_then(|n| n.to_str()) {
                            it.name = n.to_owned();
                        }
                        it.path = Some(o.path);
                        it.size = Some(o.size);
                        it.downloaded = o.size;
                        it.status = Status::Complete;
                        it.error = None;
                        it.finished_at = Some(now_ms());
                    }
                    // The user's pause (or resume, meanwhile) already set the status.
                    End::Cancelled => {
                        if it.status == Status::Downloading {
                            it.status = Status::Paused;
                        }
                    }
                    End::Failed(msg) => {
                        // Failing while the user was pausing is the pause.
                        if it.status == Status::Downloading {
                            it.status = Status::Failed;
                            it.error = Some(msg);
                        }
                    }
                }
            }
            self.save(&mut st, true);
        }
        self.pump();
    }
}

/// Run one download and keep its list entry up to date until it ends.
async fn supervise(inner: Arc<Inner>, l: Launch, opts: Options, stop: CancellationToken) -> End {
    let dl = match launch(&l, opts) {
        Ok(d) => d,
        Err(e) => return End::Failed(e),
    };
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut cancelled = false;
    let mut last = (Instant::now(), l.start_bytes);
    let mut speed = 0.0f64;
    loop {
        tokio::select! {
            _ = tick.tick() => {}
            _ = stop.cancelled(), if !cancelled => {
                dl.cancel();
                cancelled = true;
            }
        }
        let snap = dl.snapshot();
        let dt = last.0.elapsed().as_secs_f64().max(1e-3);
        let inst = snap.downloaded.saturating_sub(last.1) as f64 / dt;
        speed = if speed == 0.0 { inst } else { 0.7 * speed + 0.3 * inst };
        last = (Instant::now(), snap.downloaded);
        inner.on_progress(l.id, &snap, speed as u64);
        if matches!(
            snap.state,
            DlState::Done | DlState::Failed | DlState::Cancelled
        ) {
            break;
        }
    }
    match dl.wait().await {
        Ok(o) => End::Done(o),
        Err(mdm_engine::Error::Cancelled) => End::Cancelled,
        Err(e) => End::Failed(e.to_string()),
    }
}

/// Resume from the saved state when there is one, else start over (into the same file name when
/// it was already chosen).
fn launch(l: &Launch, mut opts: Options) -> Result<Download, String> {
    opts.checksum = l.checksum.as_deref().and_then(mdm_engine::verify::Hash::parse);
    let url: Url = l.url.parse().map_err(|e| format!("not a valid address: {e}"))?;
    let headers = headers(l.referer.as_deref());
    let dest = match &l.path {
        Some(path) => {
            let sp = state::state_path(path);
            if sp.exists() {
                match Download::resume(sp.clone(), headers.clone(), opts.clone()) {
                    Ok(d) => return Ok(d),
                    Err(e) => log::warn!("{}: cannot resume ({e}); starting over", path.display()),
                }
            }
            remove_leftovers(path);
            let _ = std::fs::remove_file(path);
            Destination::File(path.clone())
        }
        None => Destination::Dir(l.dir.clone()),
    };
    Ok(Download::start(Request { url, headers, dest }, opts))
}
