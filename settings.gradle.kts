pluginManagement {
    repositories {
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

rootProject.name = "Candle Fun"
include(":network")
include(":platform")
include(":marketdata-domain")
include(":marketdata-impl")
include(":account-domain")
include(":account-impl")
include(":execution-domain")
include(":execution-impl")
include(":features")
include(":avellaneda-mm")
