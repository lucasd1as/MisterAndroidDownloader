pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Kotlin half of rustls-platform-verifier (the Rust TLS stack asks Android's own
        // trust store through it), vendored so a build needs no GitHub download.
        maven { url = uri("maven-repo") }
    }
}

rootProject.name = "MisterAndroidDownloader"
include(":app")
