plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.marketdata.impl.recording.MarketStateRecorderRunner")
}

tasks.register<JavaExec>("record") {
    group = "application"
    description = "Record MarketState for a fixed duration (seconds). Use -PdurationSec=..."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.marketdata.impl.recording.MarketStateRecorderRunner")
    val duration = (project.findProperty("durationSec") as String?) ?: "600"
    environment("DURATION_SEC", duration)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":marketdata-domain"))
    implementation(project(":network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.koin.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
