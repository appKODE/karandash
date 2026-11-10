plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
    id("karandash.lint")
    id("dev.detekt")
    id("karandash.kover")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    // Shadow 9 defaults can drop duplicate service descriptors from project dependencies
    // before transformers run. Keep duplicates only for service descriptors so
    // mergeServiceFiles can aggregate module entries without duplicating other resources.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
}

configure<dev.detekt.gradle.extensions.DetektExtension> {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoredBuildTypes = listOf("release")
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
    )
}
