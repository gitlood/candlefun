plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.ofi.kukanov.OfiBacktestRunner")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":account-domain"))
    implementation(project(":account-impl"))
    implementation(project(":marketdata-domain"))
    implementation(project(":marketdata-impl"))
    implementation(project(":execution-domain"))
    implementation(project(":execution-impl"))
    implementation(project(":network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runLive") {
    group = "application"
    description = "Run OFI live paper runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.ofi.kukanov.OfiLiveRunner")
}
