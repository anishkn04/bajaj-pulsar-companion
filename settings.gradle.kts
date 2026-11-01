pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pulsar-connect"

include(":core:model")
include(":core:session")
include(":core:network")
include(":core:data")
include(":core:data-jdbc")
include(":app")
