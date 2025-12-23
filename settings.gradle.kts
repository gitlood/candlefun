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
include(":app")
include(":app:historicaldata")
include(":app:network")
include(":app:platformutil")
include(":app:stackeddca")
include(":app:algo")
include(":app:tradebot")
include(":app:orderbookscalper")
include(":app:liveklines")
