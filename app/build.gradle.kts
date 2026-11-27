plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.saimum.callmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saimum.callmanager"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.9"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Produces 5 APK variants per build type (arm64-v8a, armeabi-v7a, x86,
    // x86_64, plus a universal one covering all of them) — 10 APKs total
    // across debug + release.
    //
    // Honest note: this app has zero native (C/C++) code, so right now
    // every ABI-specific APK is functionally identical to the universal
    // one — ABI splitting only reduces size when there are per-architecture
    // native libraries to exclude. It's wired up now because it's a real
    // build requirement and costs nothing, and it's already correct for
    // the day native code (a codec, a native library dependency, etc.)
    // gets added.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Release signing. The values come from environment variables, which the
    // GitHub Actions workflow fills from repository Secrets (KEYSTORE_PATH is
    // the decoded keystore file; KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD
    // are secrets). If any of them is missing (local builds, PRs from forks)
    // no signing config is created and the release APK stays unsigned instead
    // of the build failing.
    val ksPath: String? = System.getenv("KEYSTORE_PATH")
    val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD")
    val ksAlias: String? = System.getenv("KEY_ALIAS")
    val ksKeyPassword: String? = System.getenv("KEY_PASSWORD")
    val canSignRelease =
        !ksPath.isNullOrBlank() &&
            !ksPassword.isNullOrBlank() &&
            !ksAlias.isNullOrBlank() &&
            !ksKeyPassword.isNullOrBlank() &&
            file(ksPath).exists()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the release keystore when the signing environment
            // variables are present (app-<abi>-release.apk); otherwise the
            // APKs come out unsigned (app-<abi>-release-unsigned.apk).
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Feature/architecture modules.
    implementation(project(":core-telephony"))
    implementation(project(":core-recording"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.ui.tooling)
}
