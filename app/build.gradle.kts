plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun String.escapedBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun providerValue(name: String): String {
    return providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: ""
}

android {
    namespace = "com.askmyscreenshots.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.askmyscreenshots.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val llmRewriteEndpoint = providerValue("ASK_SCREENSHOTS_LLM_REWRITE_ENDPOINT")
        val llmRewriteApiKey = providerValue("ASK_SCREENSHOTS_LLM_REWRITE_API_KEY")
        val geminiApiKey = providerValue("ASK_SCREENSHOTS_GEMINI_API_KEY")
        buildConfigField("String", "LLM_REWRITE_ENDPOINT", llmRewriteEndpoint.escapedBuildConfigString())
        buildConfigField("String", "LLM_REWRITE_API_KEY", llmRewriteApiKey.escapedBuildConfigString())
        buildConfigField("Boolean", "LLM_REWRITE_ENABLED", (llmRewriteEndpoint.isNotBlank() && llmRewriteApiKey.isNotBlank()).toString())
        buildConfigField("String", "GEMINI_API_KEY", geminiApiKey.escapedBuildConfigString())
        buildConfigField("Boolean", "GEMINI_REWRITE_ENABLED", geminiApiKey.isNotBlank().toString())

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(project(":screenshot-skill"))

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    ksp("androidx.room:room-compiler:2.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
