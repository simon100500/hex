plugins {
    id("com.android.application")
}

android {
    namespace = "app.hexforandroid"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.gigaam.dictation"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from the environment (android/release.sh reads the
    // keystore password from the keyring). Without it, release is unsigned.
    val keystore = System.getenv("HEX_RELEASE_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("HEX_RELEASE_PASSWORD")
            keyAlias = System.getenv("HEX_RELEASE_ALIAS") ?: "hex"
            keyPassword = System.getenv("HEX_RELEASE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // JNI looks HexCore's natives up by name; nothing to gain from shrinking.
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// libhexcore.so comes from ../core through cargo-ndk. Pass -PskipCore to
// package whatever jniLibs already holds.
val buildCore by tasks.registering(Exec::class) {
    description = "Builds Hex's Rust core for arm64-v8a (three CPU variants)"
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("build-core.sh").absolutePath)
    onlyIf { !project.hasProperty("skipCore") }
    // Up to date unless the core, Hex's text steps or the script changed.
    inputs.dir(rootProject.file("core/src"))
    inputs.files(rootProject.file("core/Cargo.toml"), rootProject.file("core/Cargo.lock"), rootProject.file("core/build.rs"), rootProject.file("build-core.sh"))
    inputs.dir(rootProject.file("../src/dictation"))
    outputs.dir(file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildCore) }
