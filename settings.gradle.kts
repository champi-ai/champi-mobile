pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "champi-mobile"

include(":app")
include(":overlay")
include(":character")
include(":assistant")
include(":providers:api")
include(":providers:edge")
include(":providers:remote")
include(":audio")
include(":actions")
include(":context")
include(":core")
