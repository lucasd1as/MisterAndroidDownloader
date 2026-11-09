//! The download manager against the engine's own lab server: real HTTP range traffic, a
//! throttled server, pausing, and a process that dies in the middle of a download.

use std::path::Path;
use std::time::{Duration, Instant};

use mdm_android::manager::{Config, Manager};
use mdm_android::model::{Item, Settings, Status};
use mdm_labserver::{Config as LabConfig, Faults, LabServer, content};
use tokio::runtime::Runtime;

struct Lab {
    // The server's tasks run on this runtime's workers while the test thread blocks.
    rt: Runtime,
    server: LabServer,
}

fn lab(faults: &str) -> Lab {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    let server = rt
        .block_on(LabServer::start(LabConfig {
            faults: Faults::parse(faults).unwrap(),
            ..LabConfig::default()
        }))
        .unwrap();
    Lab { rt, server }
}

impl Lab {
    fn url(&self, path: &str) -> String {
        self.server.url(path)
    }
}

impl Drop for Lab {
    fn drop(&mut self) {
        // Keep `rt` alive until the server is gone.
        let _ = &self.rt;
    }
}

fn manager(dir: &Path) -> Manager {
    Manager::new(Config {
        data_dir: dir.join("data"),
        download_dir: dir.join("dl"),
        user_agent: "mdm-android-test".into(),
    })
    .unwrap()
}

fn items(m: &Manager) -> Vec<Item> {
    m.list().items
}

fn item(m: &Manager, id: u64) -> Item {
    items(m).into_iter().find(|i| i.id == id).expect("item")
}

fn wait_for(what: &str, timeout: Duration, mut cond: impl FnMut() -> bool) {
    let start = Instant::now();
    while !cond() {
        assert!(start.elapsed() < timeout, "timed out waiting for: {what}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn check_content(path: &Path, seed: u64, size: usize) {
    let data = std::fs::read(path).unwrap();
    assert_eq!(data.len(), size, "size");
    let mut want = vec![0; size];
    content::fill(seed, 0, &mut want);
    assert!(data == want, "content differs from the generator's");
}

const MIB: usize = 1024 * 1024;

#[test]
fn downloads_a_file_and_remembers_it() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let id = m.add(&lab.url("/gen/4M/photo.bin?seed=3"), None, None).unwrap();
    wait_for("complete", Duration::from_secs(30), || {
        item(&m, id).status == Status::Complete
    });
    let it = item(&m, id);
    assert_eq!(it.size, Some(4 * MIB as u64));
    assert_eq!(it.downloaded, 4 * MIB as u64);
    assert_eq!(it.name, "photo.bin");
    assert!(it.finished_at.is_some());
    check_content(it.path.as_ref().unwrap(), 3, 4 * MIB);
    assert_eq!(m.list().active, 0);
    drop(m);

    // A new process sees the same list.
    let m = manager(tmp.path());
    let it = item(&m, id);
    assert_eq!(it.status, Status::Complete);
    assert_eq!(it.name, "photo.bin");
    // And ids keep counting.
    let next = m.add(&lab.url("/gen/1M/b.bin?seed=1"), None, None).unwrap();
    assert!(next > id);
}

#[test]
fn pause_keeps_the_data_and_resume_finishes_the_file() {
    // 1 MB/s per response: slow enough to stop in the middle.
    let lab = lab("rate=1M");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let id = m.add(&lab.url("/gen/16M/big.bin?seed=7"), None, None).unwrap();
    wait_for("some progress", Duration::from_secs(30), || {
        item(&m, id).downloaded > 2 * MIB as u64
    });
    m.pause(id);
    assert_eq!(item(&m, id).status, Status::Paused);
    wait_for("the slot to be free", Duration::from_secs(10), || {
        // Nothing moves any more.
        let a = item(&m, id).downloaded;
        std::thread::sleep(Duration::from_millis(700));
        item(&m, id).downloaded == a
    });
    let at_pause = item(&m, id).downloaded;
    assert!(at_pause > 0 && at_pause < 16 * MIB as u64);
    assert_eq!(item(&m, id).speed, 0);

    m.resume(id);
    wait_for("complete", Duration::from_secs(60), || {
        item(&m, id).status == Status::Complete
    });
    check_content(item(&m, id).path.as_ref().unwrap(), 7, 16 * MIB);
}

#[test]
fn a_download_cut_off_by_the_process_dying_continues_at_the_next_start() {
    let lab = lab("rate=1M");
    let tmp = tempfile::tempdir().unwrap();
    let id;
    {
        let m = manager(tmp.path());
        id = m.add(&lab.url("/gen/12M/killed.bin?seed=9"), None, None).unwrap();
        wait_for("progress saved", Duration::from_secs(30), || {
            // The engine saves its state every 2 s; wait until it has.
            let it = item(&m, id);
            it.downloaded > 3 * MIB as u64
                && it
                    .path
                    .as_ref()
                    .is_some_and(|p| mdm_engine_state(p).exists())
        });
        assert_eq!(item(&m, id).status, Status::Downloading);
        // The manager (and its runtime) goes away with the download unfinished.
    }
    let m = manager(tmp.path());
    // Running when it stopped: queued again, and started at once.
    assert!(matches!(
        item(&m, id).status,
        Status::Queued | Status::Downloading
    ));
    wait_for("complete", Duration::from_secs(60), || {
        item(&m, id).status == Status::Complete
    });
    check_content(item(&m, id).path.as_ref().unwrap(), 9, 12 * MIB);
}

fn mdm_engine_state(dest: &Path) -> std::path::PathBuf {
    let mut s = dest.as_os_str().to_owned();
    s.push(".mdm.json");
    s.into()
}

#[test]
fn only_max_active_downloads_run_at_once() {
    let lab = lab("rate=512K");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    m.set_settings(Settings {
        max_active: 1,
        ..Settings::default()
    })
    .unwrap();
    let ids: Vec<u64> = (0..3)
        .map(|n| {
            m.add(&lab.url(&format!("/gen/4M/f{n}.bin?seed={n}")), None, None)
                .unwrap()
        })
        .collect();
    std::thread::sleep(Duration::from_millis(800));
    let list = items(&m);
    assert_eq!(
        list.iter().filter(|i| i.status == Status::Downloading).count(),
        1
    );
    assert_eq!(list.iter().filter(|i| i.status == Status::Queued).count(), 2);

    // Raising the limit starts the others.
    m.set_settings(Settings {
        max_active: 3,
        ..Settings::default()
    })
    .unwrap();
    wait_for("all three running", Duration::from_secs(10), || {
        items(&m)
            .iter()
            .filter(|i| i.status == Status::Downloading)
            .count()
            == 3
    });
    m.pause_all();
    assert!(items(&m).iter().all(|i| i.status == Status::Paused));
    m.resume_all();
    wait_for("all complete", Duration::from_secs(90), || {
        items(&m).iter().all(|i| i.status == Status::Complete)
    });
    for (n, id) in ids.iter().enumerate() {
        check_content(item(&m, *id).path.as_ref().unwrap(), n as u64, 4 * MIB);
    }
}

#[test]
fn a_server_error_fails_the_download_and_retry_works() {
    // The first requests fail with 503, more than the engine retries.
    let lab = lab("fail_first=1000");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let id = m.add(&lab.url("/gen/1M/x.bin?seed=2"), None, None).unwrap();
    wait_for("failure", Duration::from_secs(60), || {
        item(&m, id).status == Status::Failed
    });
    assert!(item(&m, id).error.is_some());

    // The server recovers.
    lab.server.set_faults(Faults::default());
    m.resume(id);
    wait_for("complete", Duration::from_secs(30), || {
        item(&m, id).status == Status::Complete
    });
    assert!(item(&m, id).error.is_none());
    check_content(item(&m, id).path.as_ref().unwrap(), 2, MIB);
}

#[test]
fn removing_a_download_deletes_what_it_left() {
    let lab = lab("rate=1M");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let id = m.add(&lab.url("/gen/16M/gone.bin?seed=4"), None, None).unwrap();
    wait_for("progress", Duration::from_secs(30), || {
        item(&m, id).downloaded > MIB as u64
    });
    m.remove(id, false);
    assert!(items(&m).is_empty());
    wait_for("folder to be empty", Duration::from_secs(10), || {
        std::fs::read_dir(tmp.path().join("dl")).unwrap().count() == 0
    });
    // And the list on disk agrees.
    drop(m);
    assert!(items(&manager(tmp.path())).is_empty());
}

#[test]
fn a_finished_file_is_exported_and_its_copy_freed() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let id = m.add(&lab.url("/gen/1M/e.bin?seed=1"), None, None).unwrap();
    wait_for("complete", Duration::from_secs(30), || {
        item(&m, id).status == Status::Complete
    });
    let path = item(&m, id).path.unwrap();
    assert!(path.exists());
    m.set_exported(id, "content://media/external/downloads/42".into())
        .unwrap();
    let it = item(&m, id);
    assert_eq!(it.exported.as_deref(), Some("content://media/external/downloads/42"));
    assert!(it.path.is_none());
    assert!(!path.exists());
    assert_eq!(m.clear_completed(), 1);
    assert!(items(&m).is_empty());
}

#[test]
fn only_downloadable_addresses_are_accepted_and_duplicates_are_not_added_twice() {
    let lab = lab("rate=256K");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    assert!(m.add("magnet:?xt=urn:btih:abc", None, None).is_err());
    assert!(m.add("not an address", None, None).is_err());
    assert!(m.add("file:///etc/passwd", None, None).is_err());
    let url = lab.url("/gen/8M/d.bin?seed=1");
    let a = m.add(&url, None, None).unwrap();
    let b = m.add(&url, None, None).unwrap();
    assert_eq!(a, b);
    assert_eq!(items(&m).len(), 1);
}

#[test]
fn probe_reports_name_and_size() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let p = m.probe(&lab.url("/gen/2M/report.pdf?seed=1"), None).unwrap();
    assert_eq!(p.name, "report.pdf");
    assert_eq!(p.size, Some(2 * MIB as u64));
    assert!(m.probe("gopher://x", None).is_err());
}

#[test]
fn the_json_api_round_trips() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let call = |method: &str, args: &str| -> serde_json::Value {
        serde_json::from_str(&mdm_android::rpc::call(&m, method, args)).unwrap()
    };
    let added = call(
        "add",
        &serde_json::json!({ "url": lab.url("/gen/1M/j.bin?seed=1") }).to_string(),
    );
    assert_eq!(added["ok"], true);
    let id = added["result"]["id"].as_u64().unwrap();
    wait_for("complete", Duration::from_secs(30), || {
        call("list", "")["result"]["items"][0]["status"] == "complete"
    });
    let listing = call("list", "");
    assert_eq!(listing["result"]["items"][0]["id"], id);
    assert_eq!(listing["result"]["active"], 0);
    assert_eq!(call("set_settings", r#"{"max_connections":99,"speed_limit":0}"#)["result"]["max_connections"], 32);
    assert_eq!(call("nope", "")["ok"], false);
    assert_eq!(call("pause", "{}")["ok"], false);
    assert_eq!(call("add", "{not json")["ok"], false);
}

fn sha256_hex(seed: u64, size: usize) -> String {
    use sha2::{Digest, Sha256};
    let mut data = vec![0; size];
    content::fill(seed, 0, &mut data);
    Sha256::digest(&data).iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn a_chosen_folder_gets_the_file_directly_with_nothing_to_copy() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let roms = tmp.path().join("roms/switch");
    m.set_settings(Settings {
        save_dir: roms.to_string_lossy().into_owned(),
        ..Settings::default()
    })
    .unwrap();
    let id = m.add(&lab.url("/gen/4M/game.xci?seed=5"), None, None).unwrap();
    wait_for("complete", Duration::from_secs(30), || {
        item(&m, id).status == Status::Complete
    });
    let it = item(&m, id);
    assert!(it.in_place);
    assert_eq!(it.path.as_deref(), Some(roms.join("game.xci").as_path()));
    check_content(&roms.join("game.xci"), 5, 4 * MIB);
    // Nothing went to the app's own download folder, and no part or state file is left.
    assert_eq!(std::fs::read_dir(tmp.path().join("dl")).unwrap().count(), 0);
    assert_eq!(std::fs::read_dir(&roms).unwrap().count(), 1);
}

#[test]
fn a_folder_that_cannot_be_written_is_refused() {
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let file = tmp.path().join("a-file");
    std::fs::write(&file, b"x").unwrap();
    // A folder cannot be created under a file.
    let err = m
        .set_settings(Settings {
            save_dir: file.join("sub").to_string_lossy().into_owned(),
            ..Settings::default()
        })
        .unwrap_err();
    assert!(err.contains("cannot write"), "{err}");
    assert!(m.settings().save_dir.is_empty());
}

#[test]
fn the_checksum_is_verified_and_a_wrong_one_fails_the_download() {
    let lab = lab("");
    let tmp = tempfile::tempdir().unwrap();
    let m = manager(tmp.path());
    let good = m
        .add(&lab.url("/gen/2M/ok.bin?seed=6"), None, Some(format!("sha256:{}", sha256_hex(6, 2 * MIB))))
        .unwrap();
    let bad = m
        .add(&lab.url("/gen/2M/bad.bin?seed=7"), None, Some(format!("sha256:{}", sha256_hex(6, 2 * MIB))))
        .unwrap();
    wait_for("both finished", Duration::from_secs(30), || {
        !item(&m, good).is_running_or_queued() && !item(&m, bad).is_running_or_queued()
    });
    assert_eq!(item(&m, good).status, Status::Complete);
    assert_eq!(item(&m, bad).status, Status::Failed);
    assert!(item(&m, bad).error.unwrap().to_lowercase().contains("checksum"));
    assert!(m.add(&lab.url("/gen/1M/c.bin?seed=1"), None, Some("not hex".into())).is_err());
}

trait Running {
    fn is_running_or_queued(&self) -> bool;
}
impl Running for Item {
    fn is_running_or_queued(&self) -> bool {
        matches!(self.status, Status::Queued | Status::Downloading)
    }
}

/// `cargo test --release -p mdm-android --test manager -- --ignored --nocapture`: how fast the
/// queue and engine move bytes when the network is not the limit (loopback), per connection count.
#[test]
#[ignore]
fn loopback_throughput() {
    let lab = lab("");
    for connections in [1u32, 8, 16, 32] {
        let tmp = tempfile::tempdir().unwrap();
        let m = manager(tmp.path());
        m.set_settings(Settings {
            max_connections: connections,
            ..Settings::default()
        })
        .unwrap();
        let start = Instant::now();
        let id = m.add(&lab.url("/gen/2G/rom.bin?seed=1"), None, None).unwrap();
        wait_for("complete", Duration::from_secs(300), || {
            item(&m, id).status == Status::Complete
        });
        let secs = start.elapsed().as_secs_f64();
        println!("{connections:>2} connections: 2 GiB in {secs:.1}s = {:.0} MB/s", 2147.5 / secs);
    }
}
