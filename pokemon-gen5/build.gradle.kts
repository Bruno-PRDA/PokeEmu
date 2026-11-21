plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":pokemon-core"))
    // The DS file system, archives and the Gen 4 record format Gen 5 builds on.
    api(project(":pokemon-gen4"))
    // GBA Pokémon reach Gen 5 through Gen 4 (Pal Park, then Poké Transfer).
    implementation(project(":pokemon-gen3"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
