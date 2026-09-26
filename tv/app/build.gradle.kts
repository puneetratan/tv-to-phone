plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flick.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flick.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The kiosk page is shared with the Pi receiver rather than copied, so the
    // persistent-page behaviour has a single source of truth. Only
    // receiver.html lives in pi/static today.
    sourceSets.getByName("main").assets.srcDir("../../pi/static")
}

dependencies {
    // 2.3.1 is the last release before the package layout changed, and it is
    // pure Java, so nothing here needs an armeabi-v7a native build.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
