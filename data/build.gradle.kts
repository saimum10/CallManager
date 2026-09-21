plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.saimum.callmanager.data"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Implements core-recording's RecordingPreferencesStore /
    // RecordingLibraryStore interfaces — depending "up" on the feature
    // module here (instead of the feature module depending down on data)
    // keeps core-recording testable and free of storage-tech concerns.
    implementation(project(":core-recording"))

    // Implements core-telephony's ForwardingPreferencesStore — same
    // dependency-inversion reasoning.
    implementation(project(":core-telephony"))
}
