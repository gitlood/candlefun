plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.pairs.PairsBacktestRunner")
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
    description = "Run pairs live paper runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.pairs.PairsLiveRunner")
}

tasks.register<JavaExec>("runTestnet") {
    group = "application"
    description = "Run pairs testnet runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.pairs.PairsTestnetRunner")
}

tasks.register<JavaExec>("runBacktest") {
    group = "application"
    description = "Run pairs backtest runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.pairs.PairsBacktestRunner")
}
