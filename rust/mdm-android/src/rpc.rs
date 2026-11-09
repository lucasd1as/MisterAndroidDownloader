//! The whole native API as one function: `call(method, json args) -> json`. The app's Kotlin
//! side sends and reads JSON, so the JNI surface stays two functions and everything else is
//! plain Rust that the tests drive directly.

use std::panic::{AssertUnwindSafe, catch_unwind};

use serde_json::{Value, json};

use crate::manager::Manager;
use crate::model::Settings;

/// `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`.
pub fn call(m: &Manager, method: &str, args: &str) -> String {
    let out = match catch_unwind(AssertUnwindSafe(|| dispatch(m, method, args))) {
        Ok(Ok(v)) => json!({ "ok": true, "result": v }),
        Ok(Err(e)) => json!({ "ok": false, "error": e }),
        Err(_) => json!({ "ok": false, "error": "internal error" }),
    };
    out.to_string()
}

fn dispatch(m: &Manager, method: &str, args: &str) -> Result<Value, String> {
    let a: Value = if args.trim().is_empty() {
        Value::Null
    } else {
        serde_json::from_str(args).map_err(|e| format!("bad arguments: {e}"))?
    };
    let id = || {
        a.get("id")
            .and_then(Value::as_u64)
            .ok_or_else(|| "missing id".to_owned())
    };
    let text = |key: &str| a.get(key).and_then(Value::as_str).map(str::to_owned);
    let need = |key: &str| text(key).ok_or_else(|| format!("missing {key}"));
    let ok = || Ok(Value::Null);

    match method {
        "list" => to_value(&m.list()),
        "add" => Ok(json!({ "id": m.add(&need("url")?, text("referer"))? })),
        "probe" => to_value(&m.probe(&need("url")?, text("referer"))?),
        "pause" => {
            m.pause(id()?);
            ok()
        }
        "resume" => {
            m.resume(id()?);
            ok()
        }
        "pause_all" => {
            m.pause_all();
            ok()
        }
        "resume_all" => {
            m.resume_all();
            ok()
        }
        "remove" => {
            let delete = a.get("delete_files").and_then(Value::as_bool).unwrap_or(false);
            m.remove(id()?, delete);
            ok()
        }
        "clear_completed" => Ok(json!({ "removed": m.clear_completed() })),
        "set_exported" => {
            m.set_exported(id()?, need("uri")?)?;
            ok()
        }
        "settings" => to_value(&m.settings()),
        "set_settings" => {
            let s: Settings =
                serde_json::from_value(a.clone()).map_err(|e| format!("bad settings: {e}"))?;
            m.set_settings(s);
            to_value(&m.settings())
        }
        _ => Err(format!("unknown method `{method}`")),
    }
}

fn to_value<T: serde::Serialize>(v: &T) -> Result<Value, String> {
    serde_json::to_value(v).map_err(|e| e.to_string())
}
