plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("src/main/java")
    }
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
