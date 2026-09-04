plugins {
    id("champi.android.library")
    id("champi.android.hilt")
}

android {
    namespace = "ai.champi.context"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":assistant"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
