plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "app.tagplayer.desktop.MainKt"
    }
}
