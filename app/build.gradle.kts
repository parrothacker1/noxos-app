plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.noxos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noxos.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "2.2.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("NOXOS_KEYSTORE_PATH") ?: "debug.keystore")
            storePassword = System.getenv("NOXOS_KEYSTORE_PASSWORD")
            keyAlias = "platform"
            keyPassword = System.getenv("NOXOS_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("NOXOS_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    implementation(project(":trigger-router"))
    implementation(project(":netmonitor"))
    implementation(project(":audit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
}
