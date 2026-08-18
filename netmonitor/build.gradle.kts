plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noxos.netmonitor"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":audit"))

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Unit tests (PacketUtils is plain Kotlin, no Android deps -> no Robolectric needed)
    testImplementation(libs.junit)
}
