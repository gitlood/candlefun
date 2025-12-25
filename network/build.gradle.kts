plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.example.network.candlecollector.UniversalCandleCollector")
}

tasks.named<JavaExec>("run") {
    environment("CANDLE_DB_PATH", "${rootProject.projectDir}/candles.db")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":marketdata-domain"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.datetime)

    implementation(libs.koin.core)

    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
