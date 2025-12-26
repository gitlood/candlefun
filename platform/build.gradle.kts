plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

dependencies {
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
