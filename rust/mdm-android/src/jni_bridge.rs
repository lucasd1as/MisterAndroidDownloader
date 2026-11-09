//! The two native functions of `io.github.lucasd1as.mdm.Native`.

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JObject, JString};
use jni::{EnvUnowned, jni_mangle};

/// `Native.nativeInit(context, configJson): String`
#[jni_mangle("io.github.lucasd1as.mdm.Native")]
pub fn native_init<'l>(
    mut env: EnvUnowned<'l>,
    _this: JObject<'l>,
    context: JObject<'l>,
    config: JString<'l>,
) -> JString<'l> {
    env.with_env(|env| {
        let config = config.try_to_string(env)?;
        platform_init(env, context);
        env.new_string(crate::init(&config))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `Native.nativeCall(method, argsJson): String`
#[jni_mangle("io.github.lucasd1as.mdm.Native")]
pub fn native_call<'l>(
    mut env: EnvUnowned<'l>,
    _this: JObject<'l>,
    method: JString<'l>,
    args: JString<'l>,
) -> JString<'l> {
    env.with_env(|env| {
        let method = method.try_to_string(env)?;
        let args = args.try_to_string(env)?;
        env.new_string(crate::call(&method, &args))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Logging to logcat, a panic hook, and the certificate verifier's handles to the JVM (HTTPS
/// cannot be checked before it).
#[cfg(target_os = "android")]
fn platform_init(env: &mut jni::Env<'_>, context: JObject<'_>) {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("MDM"),
        );
        std::panic::set_hook(Box::new(|info| log::error!("panic: {info}")));
    });
    if let Err(e) = rustls_platform_verifier::android::init_with_env(env, context) {
        log::error!("certificate verifier: {e}");
    }
}

#[cfg(not(target_os = "android"))]
fn platform_init(_env: &mut jni::Env<'_>, _context: JObject<'_>) {}
