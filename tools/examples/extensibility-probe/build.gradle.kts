import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// One Gradle source set per probe item, so each compiles (and goes red/green) independently:
//
//   baseline  - existing SDK API only; must always compile (a failure is a setup error)
//   rule      - run context: RunContext.enrichment via validate(..., run: RunContext)
//   analyzer  - typed meta keys: MetaKey<T> + PresentationKeys.SECTION
//   output    - output hints: OutputModule.preferredFormat / supportsFileOutput
//   config    - module-owned config: module-owned top-level `probe:` block decoded through configSerializer
//
// Compile one item:  ./gradlew -p tools/examples/extensibility-probe compileRuleKotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.karandash.probe"
version = "0.0.0"

val karandashVersion: String = providers.gradleProperty("karandashVersion").getOrElse("1.0.0")

val probeItems = listOf("baseline", "rule", "analyzer", "output", "config")

probeItems.forEach { item ->
    val sourceSet = sourceSets.create(item)
    dependencies {
        add(sourceSet.implementationConfigurationName, "ru.kode.karandash:sdk:$karandashVersion")
        add(sourceSet.implementationConfigurationName, libs.kotlinx.serialization.json)
        add(sourceSet.implementationConfigurationName, libs.kotlinx.coroutines.core)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
