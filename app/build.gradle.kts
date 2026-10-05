plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing. Local builds use app/keystore.jks with the historical "android"
// passwords (build.sh creates it); CI overrides via env vars (see
// .github/workflows/release.yml): ECHOCLOCK_KEYSTORE_FILE, ECHOCLOCK_KEYSTORE_PASSWORD,
// ECHOCLOCK_KEY_ALIAS, ECHOCLOCK_KEY_PASSWORD. Gradle properties of the same names are
// accepted as a fallback. A missing keystore falls back to the debug signing key.
val keystoreFile = file(
    providers.environmentVariable("ECHOCLOCK_KEYSTORE_FILE").orNull
        ?: providers.gradleProperty("echoclockKeystoreFile").orNull
        ?: "keystore.jks"
)
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

