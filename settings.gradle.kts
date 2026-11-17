rootProject.name = "DDLauncher"
include(":app")
include(":steam-protocol")
include(":workshop-core")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
