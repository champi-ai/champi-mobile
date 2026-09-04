import java.util.Properties

plugins {
    id("champi.android.library")
    id("champi.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// Read from local.properties (gitignored) rather than committing an endpoint/key to source —
// empty defaults mean CI/other machines just get an always-unavailable RemoteLlmProvider instead
// of a build failure.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "ai.champi.providers.remote"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "LLM_BASE_URL", "\"${localProperties.getProperty("llm.baseUrl", "")}\"")
        buildConfigField("String", "LLM_API_KEY", "\"${localProperties.getProperty("llm.apiKey", "")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${localProperties.getProperty("llm.model", "")}\"")
    }
}

dependencies {
    implementation(project(":providers:api"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
