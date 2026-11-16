plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":pokemon-core"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
