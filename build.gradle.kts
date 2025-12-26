// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    jacoco
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        val excludes = listOf(
            "**/ai/**",
            "**/di/**",
            "**/dto/**",
            "**/config/**"
        )
        classDirectories.setFrom(
            files(classDirectories.files.map { fileTree(it) { exclude(excludes) } })
        )
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
}

tasks.register<JacocoReport>("jacocoRootReport") {
    val testTasks = subprojects.flatMap { subproject ->
        subproject.tasks.matching { it.name == "test" }
    }
    dependsOn(testTasks)

    val sourceSets = subprojects.mapNotNull { subproject ->
        subproject.extensions.findByName("sourceSets") as? SourceSetContainer
    }

    val excludes = listOf(
        "**/ai/**",
        "**/di/**",
        "**/dto/**",
        "**/config/**"
    )
    classDirectories.from(
        sourceSets.map { it.named("main").get().output }.map { fileTree(it) { exclude(excludes) } }
    )
    sourceDirectories.from(sourceSets.map { it.named("main").get().allSource.srcDirs })
    executionData.from(
        subprojects.map { subproject ->
            subproject.fileTree(subproject.buildDir).apply {
                include("jacoco/test.exec")
                include("jacoco/*.exec")
            }
        }
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register("recordAndBacktest") {
    group = "application"
    description = "Record MarketState then run Avellaneda backtest."
    dependsOn(":marketdata-impl:record", ":avellaneda-mm:run")
    val backtest = project(":avellaneda-mm").tasks.named("run")
    backtest.configure {
        mustRunAfter(":marketdata-impl:record")
    }
}
