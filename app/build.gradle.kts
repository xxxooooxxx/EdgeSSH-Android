plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lyrnox.edgessh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyrnox.edgessh"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    // TerminalView draws edge-to-edge; the app handles insets itself.
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.security.crypto)

    // SSH/SFTP (Apache-2.0)
    implementation(libs.sshj)

    // Terminal emulation + view, Termux libraries via JitPack (Apache-2.0)
    implementation(libs.termux.terminal.view)
    // Avoids Duplicate class com.google.common.util.concurrent.ListenableFuture
    implementation(libs.guava.listenablefuture)
}
