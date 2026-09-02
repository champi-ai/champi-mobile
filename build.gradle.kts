// Top-level build file. Configuration common to all sub-projects lives in
// build-logic/convention/src/main/kotlin via convention plugins.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
