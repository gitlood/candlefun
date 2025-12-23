plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.stackeddca.MainKt")
}

tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":app:historicaldata"))
    implementation(project(":app:platformutil"))
    implementation(project(":app:network"))
    implementation(project(":app:liveklines"))
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
