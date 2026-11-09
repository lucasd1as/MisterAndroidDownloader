package io.github.lucasd1as.mdm

import android.content.Context

/**
 * The Rust library (`rust/mdm-android`). Two functions only; [Downloads] wraps them in a typed API.
 * The names and signatures are what `jni_bridge.rs` exports.
 */
object Native {
    init {
        System.loadLibrary("mdm_android")
    }

    /** Starts the download manager once per process; `config` is JSON. Returns JSON. */
    external fun nativeInit(context: Context, config: String): String

    /** `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}` as JSON text. */
    external fun nativeCall(method: String, args: String): String
}
