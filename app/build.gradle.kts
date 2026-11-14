plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.fabiamonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.fabiamonitor"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
