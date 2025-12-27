plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.survivor.SurvivorTestnetRunner")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":account-domain"))
    implementation(project(":execution-domain"))
    implementation(project(":account-impl"))
    implementation(project(":execution-impl"))
    implementation(project(":marketdata-domain"))
    implementation(project(":network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runTestnet") {
    group = "application"
    description = "Run survivor testnet runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.survivor.SurvivorTestnetRunner")
}

tasks.register<JavaExec>("runBacktest") {
    group = "application"
    description = "Run survivor backtest runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.survivor.SurvivorBacktestRunner")
}

tasks.register<JavaExec>("runTail") {
    group = "application"
    description = "Run survivor CSV tail runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.survivor.SurvivorLiveRunner")
}
