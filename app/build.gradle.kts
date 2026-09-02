plugins {
    id("champi.android.application")
    id("champi.android.hilt")
}

android {
    namespace = "ai.champi.app"
    defaultConfig {
        applicationId = "ai.champi.app"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "ai.champi.app.HiltTestRunner"
    }
}

dependencies {
    implementation(project(":core"))

    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    kspAndroidTest(libs.hilt.compiler)
}
