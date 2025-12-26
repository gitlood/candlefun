plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":marketdata-domain"))
    implementation(project(":execution-domain"))
    implementation(libs.kotlinx.coroutines.core)
}
