plugins {
    id("champi.android.library")
    id("champi.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.champi.providers.api"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
