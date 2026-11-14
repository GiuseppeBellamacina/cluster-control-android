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
        versionCode = 12
        versionName = "0.5.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // still signed with the debug key (so updates install over each other) but not debuggable:
        // no run-as / debugger access to the app's data
        debug { isDebuggable = false }
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
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
}
