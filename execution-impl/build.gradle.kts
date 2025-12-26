plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":account-domain"))
    implementation(project(":execution-domain"))
    implementation(project(":network"))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(project(":account-impl"))
}
