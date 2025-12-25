plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":execution-domain"))
    implementation(project(":network"))
    implementation(libs.koin.core)
}
