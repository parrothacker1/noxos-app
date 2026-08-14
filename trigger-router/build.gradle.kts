import java.net.URL
import java.util.Base64

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noxos.triggerrouter"
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
}

// android.system.virtualmachine.* (VirtualMachine/VirtualMachineManager/VirtualMachineConfig) are
// @SystemApi — absent from the public compileSdk jar. Google's own reference app builds against
// them via Soong's sdk_version "system_current", which resolves to prebuilts/sdk/<api>/system/android.jar
// inside an AOSP tree. We're a plain Gradle project with no AOSP checkout, so fetch that same jar
// from AOSP's public git mirror instead. Verified present: android/system/virtualmachine/*.class.
val avfStubJar = layout.buildDirectory.file("avf-stubs/android-system-35.jar")

val fetchAvfSystemStub by tasks.registering {
    outputs.file(avfStubJar)
    doLast {
        val out = avfStubJar.get().asFile
        if (!out.exists()) {
            out.parentFile.mkdirs()
            val b64 = URL(
                "https://android.googlesource.com/platform/prebuilts/sdk/+/refs/heads/main/35/system/android.jar?format=TEXT"
            ).readText()
            out.writeBytes(Base64.getMimeDecoder().decode(b64))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(fetchAvfSystemStub)
}

dependencies {
    implementation(project(":audit"))
    compileOnly(files(avfStubJar))

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
