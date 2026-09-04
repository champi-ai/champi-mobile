plugins {
    id("champi.android.library")
    id("champi.android.hilt")
}

android {
    namespace = "ai.champi.providers.edge"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":providers:api"))
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
