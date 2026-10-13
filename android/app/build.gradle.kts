import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing: CI secrets win (NEWSADDA_KEYSTORE_* env); otherwise the repo keystore.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val repo = System.getenv("GITHUB_REPOSITORY") ?: "piyushpriyanshu/news-adda"

android {
    namespace = "com.newsadda.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.newsadda.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        // Default feed: published every few minutes by the "Collect news" workflow.
        // Users can point the app at a self-hosted backend in Settings.
        buildConfigField("String", "DEFAULT_FEED_URL",
            "\"https://raw.githubusercontent.com/$repo/data/feed.json\"")
        buildConfigField("String", "UPDATE_URL",
            "\"https://github.com/$repo/releases/latest/download/update.json\"")
        buildConfigField("String", "RELEASES_PAGE", "\"https://github.com/$repo/releases\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                "keystore/" + (signingValue("NEWSADDA_KEYSTORE_FILE", "storeFile") ?: "newsadda-release.jks"))
            storePassword = signingValue("NEWSADDA_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingValue("NEWSADDA_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("NEWSADDA_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("Boolean", "UPDATES_ENABLED", "true")
            resValue("string", "app_name", "न्यूज़ अड्डा")
        }
        debug {
            // Installs next to the release app; never self-updates.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("Boolean", "UPDATES_ENABLED", "false")
            resValue("string", "app_name", "न्यूज़ अड्डा (डीबग)")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
