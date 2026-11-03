plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("app.clustercontrol.sstp.LabKt") }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("e2e") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.clustercontrol.sstp.E2eKt")
}

tasks.test { useJUnitPlatform() }
