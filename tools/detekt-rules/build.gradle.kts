import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Karandash's own detekt rules (ruleset `karandash`), loaded into every detekt task by karandash.lint.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("karandash.lint")
    id("dev.detekt")
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
    // The module lints itself with its own rules: the jar alone, detekt supplies the rest.
    detektPlugins(files(tasks.jar))
}

kotlin {
    jvmToolchain(17)
    // detekt 2.0.0-alpha.6 runs these rules on its own Kotlin 2.4.10 stdlib: compile and test against exactly that
    // one, so a call into a newer stdlib API fails here instead of with NoSuchMethodError inside detekt.
    coreLibrariesVersion = "2.4.10"
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
    }
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}
