import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set(versionCatalog.findVersion("ktlint-tool").get().requiredVersion)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
        exclude("**/generated-src/**")
        exclude { element -> element.file.absolutePath.contains("/build/") }
        exclude { element -> element.file.absolutePath.contains("/generated-src/") }
    }
}

// Every detekt task loads the repo's own ruleset (tools/detekt-rules, configured under `karandash:` in detekt.yml).
// Non-transitive: detekt brings its own Kotlin stdlib and API; the rules jar is all it needs.
pluginManager.withPlugin("dev.detekt") {
    if (path != ":tools:detekt-rules") {
        dependencies {
            "detektPlugins"(project(":tools:detekt-rules")) { isTransitive = false }
        }
    }
}
