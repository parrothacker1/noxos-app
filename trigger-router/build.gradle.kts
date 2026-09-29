import java.net.URL

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noxos.triggerrouter"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        buildConfigField(
            "String",
            "MODEL_MANIFEST_URL",
            "\"https://github.com/parrothacker1/noxos-inference/releases/download/student-latest/manifest.json\""
        )
        buildConfigField(
            "String",
            "AUTOENCODER_MANIFEST_URL",
            "\"https://github.com/parrothacker1/noxos-inference/releases/download/autoencoder-latest/manifest.json\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

val avfStubJar = layout.buildDirectory.file("avf-stubs/android-system-35.jar")

val fetchAvfSystemStub by tasks.registering {
    outputs.file(avfStubJar)
    doLast {
        val out = avfStubJar.get().asFile
        if (!out.exists()) {
            out.parentFile.mkdirs()
            // android.googlesource.com had a prolonged (2h+) host-wide 503 outage on 2026-09-28;
            // this mirror serves the identical AOSP prebuilt as a plain binary download (no base64).
            URL("https://raw.githubusercontent.com/msft-mirror-aosp/platform.prebuilts.sdk/main/35/system/android.jar")
                .openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(fetchAvfSystemStub)
}

dependencies {
    implementation(project(":audit"))
    compileOnly(files(avfStubJar))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
