plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pcontrol.app"
    compileSdk = 37

    // Local release signing. "android/key.properties" is gitignored and present
    // only on dev machines; CI builds unsigned APKs (it signs with apksigner
    // separately), so this block is a no-op when the file is absent.
    // Version injected by CI from the release tag (via -PappVersionName /
    // -PappVersionCode). Defaults are for local dev builds; CI always
    // overrides with the tag version so auto-update can compare correctly.
    val appVersionName: String = project.findProperty("appVersionName") as String? ?: "0.0.5"
    val appVersionCodeRaw = project.findProperty("appVersionCode") as String?
    val appVersionCode: Int = if (appVersionCodeRaw != null) {
        val parsed = appVersionCodeRaw.toIntOrNull()
            ?: error("Invalid -PappVersionCode value: '$appVersionCodeRaw' (must be a non-negative integer)")
        if (parsed < 0) {
            error("Invalid -PappVersionCode value: '$appVersionCodeRaw' (must be >= 0, got $parsed)")
        }
        parsed
    } else {
        5
    }

    val keyProperties = rootProject.file("key.properties")
    if (keyProperties.exists()) {
        signingConfigs {
            create("release") {
                keyProperties.readText()
                    .lineSequence()
                    .filter { it.contains('=') }
                    .associate {
                        val (k, v) = it.split('=', limit = 2)
                        k.trim() to v.trim()
                    }
                    .also { props ->
                        storeFile = rootProject.file(props["storeFile"]!!)
                        storePassword = props["storePassword"]!!
                        keyAlias = props["keyAlias"]!!
                        keyPassword = props["keyPassword"]!!
                    }
            }
        }
    }

    defaultConfig {
        applicationId = "com.pcontrol.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keyProperties.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // EncryptedSharedPreferences for storing the bearer token
    implementation("androidx.security:security-crypto:1.1.0")

    // KotlinX Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // OkHttp for SyncClient
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
