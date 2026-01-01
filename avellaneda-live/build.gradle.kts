plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.example.avellaneda.AvellanedaMmLiveRunner")
}

tasks.register<JavaExec>("runTestnet") {
    group = "application"
    description = "Run Avellaneda MM with testnet execution."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.avellaneda.AvellanedaMmTestnetRunner")
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":account-domain"))
    implementation(project(":account-impl"))
    implementation(project(":marketdata-domain"))
    implementation(project(":marketdata-impl"))
    implementation(project(":execution-domain"))
    implementation(project(":execution-impl"))
    implementation(project(":avellaneda-mm"))
    implementation(project(":network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
