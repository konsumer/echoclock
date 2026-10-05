plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing.
//
// The repo ships a self-signed key, `app/release.keystore` (alias/store/key password all
// "android" — it is deliberately NOT a secret), so every build from every machine and from CI
// signs identically and APKs upgrade cleanly over each other. It is only "identity", not
// security: anyone with the repo could sign an APK your device accepts as an update of
// EchoClock. Replace it if you fork and want your own identity.
//
// Overrides, highest first: env vars (ECHOCLOCK_KEYSTORE_FILE / _PASSWORD / _ALIAS / _KEY_PASSWORD,
// as set from repo secrets in .github/workflows/release.yml), then gradle properties of the same
// names, then a legacy local app/keystore.jks. With none present we fall back to the debug key
// (installable, but not upgradeable over a release-signed install).
val keystoreFile = run {
    val explicit = providers.environmentVariable("ECHOCLOCK_KEYSTORE_FILE").orNull
        ?: providers.gradleProperty("echoclockKeystoreFile").orNull
    val candidates = listOfNotNull(explicit, "release.keystore", "keystore.jks")
    file(candidates.firstOrNull { file(it).exists() } ?: candidates.first())
}
val keystorePassword = providers.environmentVariable("ECHOCLOCK_KEYSTORE_PASSWORD").orNull
    ?: providers.gradleProperty("echoclockStorePassword").orNull
    ?: "android"
val keyAliasName = providers.environmentVariable("ECHOCLOCK_KEY_ALIAS").orNull
    ?: providers.gradleProperty("echoclockKeyAlias").orNull
    ?: "echoclock"
val keyPasswordValue = providers.environmentVariable("ECHOCLOCK_KEY_PASSWORD").orNull
    ?: providers.gradleProperty("echoclockKeyPassword").orNull
    ?: "android"

android {
    namespace = "org.echoclock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.echoclock"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Fall back to the debug key when build.sh could not create keystore.jks.
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // Flat source layout (docs/DESIGN.md §3): sources live next to build.sh.
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("src")   // org/echoclock/*.kt
        res.srcDirs("res")
        assets.srcDirs("assets")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

