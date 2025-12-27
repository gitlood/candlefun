plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.example.superbot.SuperbotKt")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
}
