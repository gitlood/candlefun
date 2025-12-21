plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.MainKt")
}

tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":app:platformutil"))
    implementation(project(":app:historicaldata"))
    implementation(libs.json)
    implementation(libs.json.jsr310)
    testImplementation(kotlin("test"))
}
