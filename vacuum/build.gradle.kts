plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("src/main/java")
    }
}

application {
    mainClass.set("com.example.vacuum.VacuumBacktestRunner")
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
    description = "Run vacuum live paper runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.vacuum.VacuumLiveRunner")
}

tasks.register<JavaExec>("runTestnet") {
    group = "application"
    description = "Run vacuum testnet runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.vacuum.VacuumTestnetRunner")
}

tasks.register<JavaExec>("runBacktest") {
    group = "application"
    description = "Run vacuum backtest runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.vacuum.VacuumBacktestRunner")
}
