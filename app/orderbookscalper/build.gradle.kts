plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.orderbookscalper.OrderBookScalperRunnerKt")
}

tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":app:network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}
