plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saimum.callmanager.recording"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // IRecorderService / IAudioSink — the Binder interface between the app and
    // the Shizuku "user service" that records call audio from the shell process.
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
