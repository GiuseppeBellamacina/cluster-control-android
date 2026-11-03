plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("com.github.mwiede:jsch:0.2.20")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.snakeyaml:snakeyaml-engine:2.8")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
