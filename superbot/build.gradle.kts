plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.example.superbot.Superbot")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(project(":account-domain"))
    implementation(project(":account-impl"))
    implementation(project(":execution-domain"))
    implementation(project(":execution-impl"))
    implementation(project(":marketdata-domain"))
    implementation(project(":marketdata-impl"))
    implementation(project(":network"))
    implementation(project(":platform"))
    implementation(project(":avellaneda-mm"))
    implementation(project(":ofi-kukanov"))
    implementation(project(":vacuum"))
    implementation(project(":pairs"))
    implementation(project(":survivor"))
}
