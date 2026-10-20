plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation(kotlin("test"))
}
