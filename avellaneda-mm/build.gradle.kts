plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.avellaneda.AvellanedaMmBacktestRunner")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":account-domain"))
    implementation(project(":account-impl"))
    implementation(project(":marketdata-domain"))
    implementation(project(":marketdata-impl"))
    implementation(project(":execution-domain"))
    implementation(project(":execution-impl"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
