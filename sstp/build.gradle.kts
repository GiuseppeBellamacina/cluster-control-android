plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("app.clustercontrol.sstp.LabKt") }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
