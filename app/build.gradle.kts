plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is opt-in via env vars. If all four are set and the keystore
// file exists, the release build is signed; otherwise it falls back to unsigned.
// CI sets these from repo secrets; locally, source ~/.config/karoo-garage/keystore.env.
val ksFileEnv: String? = System.getenv("KEYSTORE_FILE")
val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val ksKeyPassword: String? = System.getenv("KEY_PASSWORD")
val canSignRelease: Boolean =
    !ksFileEnv.isNullOrBlank() &&
        !ksPassword.isNullOrBlank() &&
        !ksAlias.isNullOrBlank() &&
        !ksKeyPassword.isNullOrBlank() &&
        file(ksFileEnv).exists()

android {
    namespace = "com.hainesy.karoogarage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hainesy.karoogarage"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = file(ksFileEnv!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.karoo.ext)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
