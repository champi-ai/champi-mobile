plugins {
    id("champi.android.library")
    id("champi.android.hilt")
}

android {
    namespace = "ai.champi.audio"
}

dependencies {
    implementation(project(":providers:api"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
