plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pokeemu.core"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Builds the libretro cores (mGBA, melonDS DS) into src/main/jniLibs if they are missing.
val buildMgbaCore by tasks.registering(Exec::class) {
    val script = rootProject.file("scripts/build-core.ps1")
    inputs.file(script)
    outputs.files(
        listOf("arm64-v8a", "x86_64").flatMap { abi ->
            listOf("libmgba_libretro.so", "libmelondsds_libretro.so").map { file("src/main/jniLibs/$abi/$it") }
        },
    )
    commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildMgbaCore) }
