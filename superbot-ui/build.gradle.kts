plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.superbot.ui.SuperbotLauncherKt")
}

dependencies {
    implementation(kotlin("stdlib"))
}
