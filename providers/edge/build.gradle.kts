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
    // ONNX Runtime is intentionally scoped to :providers:edge only — on-device inference
    // must not leak the runtime into :core or other modules.
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
