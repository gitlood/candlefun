plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.tradebot.TradeBotMainKt")
}


tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(libs.json)
    implementation(project(":app:historicaldata"))
    implementation(project(":app:platformutil"))
}
