import org.gradle.api.initialization.resolve.RepositoriesMode

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
    }
}

// Single flat module: the project directory *is* the app (see docs/DESIGN.md §3).
// Named "app" so the APK lands at build/outputs/apk/release/app-release.apk.
rootProject.name = "app"
