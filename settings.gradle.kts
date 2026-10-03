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
        // Termux terminal-emulator / terminal-view (Apache-2.0)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "EdgeSSH-Android"
include(":app")
