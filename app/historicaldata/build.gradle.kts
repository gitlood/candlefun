plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.historicaldata.MainKt")
}


tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

dependencies {
    // Exposed for database
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.xerial:sqlite-jdbc:3.36.0.3")
    implementation("org.slf4j:slf4j-simple:2.0.12")
    implementation(project(":app:network"))
    implementation(project(":app:platformutil"))
}