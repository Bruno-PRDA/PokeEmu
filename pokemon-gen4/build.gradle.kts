plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":pokemon-core"))
    // GBA -> DS transfers (Pal Park) read Gen 3 records.
    implementation(project(":pokemon-gen3"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
