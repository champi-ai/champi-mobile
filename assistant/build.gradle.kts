plugins {
    id("champi.android.library")
    id("champi.android.hilt")
}

android {
    namespace = "ai.champi.assistant"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.ktx)
}
