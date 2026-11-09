# Mister Android Downloader

An Android app (APK) built on the download engine of
[Mister Download Manager](https://github.com/anefzaoui/misterdownloadmanager) (MDM, a Windows/Linux/macOS
download manager written in Rust).

You get MDM's real engine, not a re-implementation: files are split into segments fetched over several
connections at once, slow connections are dropped and their part re-split, the number of connections each
server tolerates is learned, and a download that was cut off continues from its saved segment table.
Around it sits a touch-first native Android app.

## What the app does

- Add a download by pasting a link, **sharing** a link to the app from a browser or chat, or opening a link to
  a file (zip, apk, mp4, pdf, iso, …) with it. The add dialog asks the server for the file's name and size.
- HTTP, HTTPS, FTP and FTPS. Multi-connection, resumable, with a global speed limit.
- A queue (downloads at once is configurable), pause / resume / retry, pause all.
- Keeps running in the background as a foreground service, with a progress notification, a *Pause all* action
  and a notification when a file is finished or fails.
- Finished files are copied to the public **Downloads** folder (MediaStore: no storage permission needed) and
  open from the list or the notification.
- Survives the app being killed: the list is saved, and downloads that were running continue next time the app
  starts.

### What the desktop app has that this one does not (yet)

The engine supports more than the Android UI exposes. Not ported: torrents and magnet links, video pages
(yt-dlp / ffmpeg), debrid services, scheduler, browser extension, remote control, proxy / DNS / network-adapter
options, checksum UI, archive extraction, RSS, themes and the 21 translations (the Android UI is English).
The desktop UI itself (egui, windows, menus, tray) is deliberately not used: it is built for mouse and
keyboard and would not be usable on a phone.

Requires Android 10 (API 29) or newer. 64-bit ARM, 32-bit ARM and x86_64 are included.

## Install

- **From CI:** every push builds the APK: *Actions → Android → latest run → Artifacts*. Tags named `v*` attach
  it to a GitHub release.
- **From source:** see below. Allow “install unknown apps” for your file manager or browser, then open the APK.

The release APK is signed with the Android **debug key** unless you provide your own (see *Signing*). That is
fine for installing it yourself; it cannot be uploaded to a store, and an update signed with a different key
needs the old version uninstalled first.

## How it fits together

```
 Kotlin / Jetpack Compose UI ── DownloadService (foreground, notifications, copy to Downloads)
            │
            │  Downloads.kt: typed wrapper; JSON in, JSON out
            ▼
 Native.kt ──JNI──▶ libmdm_android.so   rust/mdm-android
                       ├─ jni_bridge.rs   nativeInit / nativeCall (the whole JNI surface)
                       ├─ rpc.rs          call(method, json) -> json
                       ├─ manager.rs      queue, one supervisor task per download, pause/resume
                       ├─ store.rs        downloads.json (atomic writes)
                       └─ mdm-engine      rust/mdm-engine: MDM's engine (vendored, see below)
```

- `rust/mdm-android` is plain Rust apart from `jni_bridge.rs`, so its behaviour is tested on the build machine
  against MDM's own lab server (real HTTP range traffic, throttling, injected faults).
- `rust/mdm-engine` is a copy of `crates/engine` from MDM at a pinned commit, with two small Android patches
  (marked `ANDROID PATCH`, listed in `rust/mdm-engine/VENDORED.md`).
- HTTPS is checked against Android's own trust store through `rustls-platform-verifier`, whose Kotlin half is
  vendored in `android/maven-repo` (the version is read from `rust/Cargo.lock`).
- Files are downloaded into the app's own external folder (the engine needs real paths for its part files),
  then copied to Downloads. A big file therefore needs free space for both for a moment.

## Build

You need: JDK 17+, the Android SDK with platform 36, build-tools 35 and **NDK 27.2.12479018**, Rust (stable,
`rustup` installs the Android targets from `rust/rust-toolchain.toml`), `cargo install cargo-ndk --locked`, and
libclang (for generating QuickJS bindings; `apt install libclang-dev` or LLVM).

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease          # all three ABIs → app/build/outputs/apk/release/app-release.apk
./gradlew -Pabis=arm64-v8a assembleDebug   # one ABI, much faster while developing
```

Gradle runs `cargo ndk` itself (the `cargoNdk` task).

### Tests

```bash
cd rust    && cargo test -p mdm-android       # queue, pause/resume, process-kill recovery, JSON API
cd android && ./gradlew testDebugUnitTest lintRelease
```

### Signing

Set `MDM_KEYSTORE`, `MDM_KEYSTORE_PASSWORD`, `MDM_KEY_ALIAS` and `MDM_KEY_PASSWORD` to sign the release APK
with your key. In CI, add the secrets `MDM_KEYSTORE_BASE64` (the keystore, base64), `MDM_KEYSTORE_PASSWORD`,
`MDM_KEY_ALIAS` and `MDM_KEY_PASSWORD`.

### Updating the engine

Copy `crates/engine/src` from a newer MDM over `rust/mdm-engine/src`, re-apply the `ANDROID PATCH` changes,
bump the commit in `VENDORED.md`, run `cargo test`.

## Not verified on a device

The Rust core and the JNI layer are tested on the build machine (including loading the real library into a
JVM). The Android parts (service, notifications, MediaStore copy, TLS through the system trust store) are
compiled, linted and packaged, but this port was built in an environment without an emulator or a phone, so they
have **not been run**. Expect to find rough edges on first use and please report them.

## Licence

The original Mister Download Manager engine is © Ahmed Nefzaoui, **GPL-3.0-or-later**, and this app contains
and links it. An APK containing it must therefore be distributed under the GPL-3.0-or-later (including making
the corresponding source available, which this repository is). The code written for this port is offered under
the MIT licence in [LICENSE](LICENSE), which is compatible; the combined app is GPL. The engine's licence text
is in `rust/mdm-engine/LICENSE`.
