# JNI: the Rust library looks these up by name.
-keep class io.github.lucasd1as.mdm.Native { *; }
# rustls-platform-verifier's Kotlin half is called from Rust, which R8 cannot see.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
