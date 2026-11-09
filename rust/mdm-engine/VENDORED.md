# Vendored copy of `mdm-engine`

Source: https://github.com/anefzaoui/misterdownloadmanager, `crates/engine`, commit
`1e4ae5881bddd1c96e39e14b8f4eb28703fcd732` (GPL-3.0-or-later, see `LICENSE`).

Differences from upstream (search for `ANDROID PATCH`):

1. `Cargo.toml`: workspace-inherited fields written out; dev-dependencies and tests dropped;
   `rquickjs-sys` with `bindgen` (no pre-generated QuickJS bindings exist for Android targets).
2. `src/ftp.rs`: Android's platform verifier has no `new_with_extra_roots` (only upstream's tests
   use extra roots), so Android uses `Verifier::new`.

To update: copy `crates/engine/src` over `src/`, re-apply the patches above, bump the commit here.
