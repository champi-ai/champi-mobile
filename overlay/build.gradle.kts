plugins {
    id("champi.android.library")
    id("champi.android.hilt")
    id("champi.android.compose")
}

android {
    namespace = "ai.champi.overlay"
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
