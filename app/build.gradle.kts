import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// release signing (keystore lives outside the repo, see keystore.properties)
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

// ---------------------------------------------------------------------------
// bundled model: copied from models/ into assets so the APK works offline on
// the very first launch. The asset itself is gitignored (30 MB binary).
// ---------------------------------------------------------------------------
val bundledModelSource = rootProject.file("models/ggml-tiny-q5_1.bin")

val prepareBundledModel = tasks.register<Copy>("prepareBundledModel") {
    description = "Copies models/ggml-tiny-q5_1.bin into the APK assets."
    doFirst {
        if (!bundledModelSource.isFile) {
            throw GradleException(
                "Model bawaan tidak ditemukan: ${bundledModelSource.absolutePath}\n" +
                    "Letakkan ggml-tiny-q5_1.bin di folder models/ sebelum build.",
            )
        }
    }
    from(bundledModelSource)
    into(layout.projectDirectory.dir("src/main/assets/models"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareBundledModel) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledModel) }

android {
    namespace = "com.ekstraktranskrip"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ekstraktranskrip"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            // Low-end target: 3-4 GB devices are arm64. Keeps APK small.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Keep native symbols sane while we are building the pipeline.
            isJniDebuggable = true
        }
    }

    androidResources {
        // The model is already quantised: compressing it wastes first-launch
        // CPU and blocks zero-copy reads. 30 MB of stored asset is cheaper.
        noCompress += "bin"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        if (!hasReleaseKeystore) {
            throw GradleException(
                "keystore.properties belum ada. Buat file itu berisi storeFile, " +
                    "storePassword, keyAlias, dan keyPassword sebelum assembleRelease.",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Pinned to the AndroidX train that still supports compileSdk 36 / AGP 8.13.
    // Compose BOM 2026.11+ requires compileSdk 37 + AGP 9.1, which brings
    // breaking DSL changes we do not need yet.
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
