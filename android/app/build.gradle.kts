import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- the Rust library (../rust/mdm-android) ----------------------------------------------------

val rustDir = rootProject.projectDir.resolve("../rust")
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")

// `-Pabis=arm64-v8a` builds one architecture only (much faster while developing).
val abis = (findProperty("abis") as String?)?.split(",")
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val cargoNdk = tasks.register<Exec>("cargoNdk") {
    description = "Builds libmdm_android.so for every ABI with cargo-ndk"
    workingDir = rustDir
    inputs.files(fileTree(rustDir) {
        include("Cargo.toml", "Cargo.lock", "mdm-android/**", "mdm-engine/**")
        exclude("**/target/**")
    })
    outputs.dir(rustJniLibs)
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
        // Not set by the SDK-less shells some CI images start Gradle from.
        System.getenv("ANDROID_HOME")?.let { environment("ANDROID_HOME", it) }
    }
    val args = mutableListOf("cargo", "ndk", "--platform", "29")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-o", rustJniLibs.get().asFile.absolutePath, "build", "--release", "-p", "mdm-android")
    commandLine(args)
}

// The Kotlin half of rustls-platform-verifier must be the version of the Rust crate in Cargo.lock.
val verifierVersion: String = run {
    val lines = rustDir.resolve("Cargo.lock").readLines()
    val i = lines.indexOfFirst { it.trim() == "name = \"rustls-platform-verifier-android\"" }
    require(i >= 0) { "rustls-platform-verifier-android is not in rust/Cargo.lock" }
    lines.drop(i + 1).first { it.startsWith("version = ") }.substringAfter('"').substringBefore('"')
}

// ---- Android -----------------------------------------------------------------------------------

android {
    namespace = "io.github.lucasd1as.mdm"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.lucasd1as.mdm"
        // Android 10: public Downloads through MediaStore, no storage permission.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }

    sourceSets["main"].jniLibs.srcDir(rustJniLibs)

    signingConfigs {
        // A release build is signed with the key in these variables; without them it is signed
        // with the debug key, which is fine for installing it yourself (not for a store).
        create("release") {
            val path = System.getenv("MDM_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("MDM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MDM_KEY_ALIAS")
                keyPassword = System.getenv("MDM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("MDM_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint { abortOnError = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    implementation("org.rustls:rustls-platform-verifier:$verifierVersion")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20250517")
}
