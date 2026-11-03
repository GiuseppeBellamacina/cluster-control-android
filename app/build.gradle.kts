import java.util.Base64
import java.util.Properties

// Build-time defaults from the gitignored secrets.properties (values are base64): personal APK only, never commit it.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun seed(key: String): String = secrets.getProperty(key)?.let { String(Base64.getDecoder().decode(it)) } ?: ""

fun lit(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.clustercontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clustercontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.2"
        for ((field, key) in listOf(
            "SEED_VPN_HOST" to "vpnHost", "SEED_VPN_USER" to "vpnUser", "SEED_VPN_PASSWORD" to "vpnPassword",
            "SEED_SSH_HOST" to "sshHost", "SEED_SSH_USER" to "sshUser", "SEED_SSH_KEY" to "sshKey",
        )) buildConfigField("String", field, lit(seed(key)))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**", "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sstp"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
}
