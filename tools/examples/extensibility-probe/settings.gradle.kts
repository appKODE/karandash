// Standalone build: deliberately NOT included in the root settings.gradle.kts.
// It sees Karandash only through the published SDK artifact in <root>/build/probe-repo,
// exactly like a third-party plugin author would. Publish it first:
//
//   ./gradlew --init-script tools/ci/extensibility-publish.init.gradle.kts publishSdkToProbeRepo
//
// Driven by tools/ci/verify-extensibility.sh.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val karandashRoot: File = settingsDir.resolve("../../..").canonicalFile

dependencyResolutionManagement {
    repositories {
        maven {
            name = "probeRepo"
            url = karandashRoot.resolve("build/probe-repo").toURI()
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files(karandashRoot.resolve("gradle/libs.versions.toml")))
        }
    }
}

rootProject.name = "extensibility-probe"
