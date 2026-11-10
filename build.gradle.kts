plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.antlr.kotlin) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.owasp.dependency.check)
}

// ── OWASP: Dependency Vulnerability Scanning ────────────────────────────────

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    suppressionFile = "tools/owasp/suppression.xml"
    analyzers.apply {
        nodeEnabled = false
        assemblyEnabled = false
        ossIndexEnabled = false
    }
    nvd.apply {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
    }
}

allprojects {
    // Kover 0.9.11+ rejects duplicate group:name identities; test-only integration projects share names with tier modules.
    group = if (path.startsWith(":plugins:integration-tests:")) "ru.kode.karandash.integration-tests" else "ru.kode.karandash"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

// Root `detekt` lints the Kotlin example plugins (standalone builds, outside settings.gradle.kts) with the
// repo rules, so `./gradlew detekt` covers them (docs/development/conventions.md "Example Plugin Canonicality").
// It also covers plugins/loader/jar-bridge, a plain kotlin-jvm module outside the karandash conventions.
detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    source.setFrom(
        fileTree("tools/examples") {
            include("*/src/**/*.kt")
            exclude("**/build/**")
        },
        "plugins/loader/jar-bridge/src/main/kotlin",
    )
}

dependencies {
    detektPlugins(project(":tools:detekt-rules")) { isTransitive = false }
}

subprojects {
    // Gradle 9 defaults failOnNoDiscoveredTests=true; keep historical behavior for empty JVM test tasks.
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
    }
}

tasks.register<Exec>("buildCustomJre") {
    group = "distribution"
    description = "Build custom JRE with jlink (~60-80MB vs ~180MB full JRE)"

    commandLine("bash", "${projectDir}/tools/jlink-config/build-jre.sh")

    environment("JAVA_HOME", System.getProperty("java.home"))

    doFirst {
        println("Building custom JRE with jlink...")
        println("Using JAVA_HOME: ${System.getProperty("java.home")}")
    }
}

// the helper scripts under tools/scripts, reachable as Gradle tasks.
tasks.register<Exec>("buildQuickjs") {
    group = "distribution"
    description = "Build the QuickJS static libraries for the host platform (tools/scripts/build-quickjs.sh)"
    commandLine("bash", "${projectDir}/tools/scripts/build-quickjs.sh")
}

tasks.register<Exec>("benchmarkDistribution") {
    group = "verification"
    description = "Benchmark the distribution artifacts (tools/scripts/benchmark.sh, -Pbench.iterations=N)"
    commandLine("bash", "${projectDir}/tools/scripts/benchmark.sh", project.findProperty("bench.iterations")?.toString() ?: "100")
}

val pluginModule: String? = project.findProperty("plugin.module")?.toString()

tasks.register<Exec>("verifyPluginStandalone") {
    group = "verification"
    description = "Check a plugin module is ready for standalone publishing (-Pplugin.module=:gradle:path)"
    doFirst { requireNotNull(pluginModule) { "pass -Pplugin.module=:gradle:module:path" } }
    commandLine("bash", "${projectDir}/tools/scripts/verify-plugin-standalone.sh", pluginModule ?: "")
}

tasks.register<Exec>("publishPluginPrepare") {
    group = "publishing"
    description = "Build a plugin's native shared library and print its registry entry (-Pplugin.module=:gradle:path)"
    doFirst { requireNotNull(pluginModule) { "pass -Pplugin.module=:gradle:module:path" } }
    commandLine("bash", "${projectDir}/tools/scripts/publish-plugin.sh", pluginModule ?: "")
}

tasks.register<Exec>("packageMacOS") {
    group = "distribution"
    description = "Build macOS DMG installer with jpackage"

    dependsOn("buildCustomJre", ":cli:shadowJar")

    commandLine("bash", "${projectDir}/tools/jlink-config/build-installer-macos.sh")

    environment("JAVA_HOME", System.getProperty("java.home"))

    doFirst {
        println("Building macOS DMG installer...")
    }
}

tasks.register<Exec>("packageLinux") {
    group = "distribution"
    description = "Build Linux DEB/RPM installers with jpackage"

    dependsOn("buildCustomJre", ":cli:shadowJar")

    commandLine("bash", "${projectDir}/tools/jlink-config/build-installer-linux.sh")

    environment("JAVA_HOME", System.getProperty("java.home"))

    doFirst {
        println("Building Linux installers...")
    }
}

tasks.register<Exec>("packageWindows") {
    group = "distribution"
    description = "Build Windows MSI installer with jpackage"

    dependsOn("buildCustomJre", ":cli:shadowJar")

    commandLine("bash", "${projectDir}/tools/jlink-config/build-installer-windows.sh")

    environment("JAVA_HOME", System.getProperty("java.home"))

    doFirst {
        println("Building Windows MSI installer...")
    }
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Build all platform installers (requires running on each platform)"

    dependsOn("buildCustomJre", ":cli:shadowJar")

    doLast {
        println("""
            |
            |Note: Cross-platform packaging requires running on each platform:
            |  - macOS: ./gradlew packageMacOS
            |  - Linux: ./gradlew packageLinux
            |  - Windows: ./gradlew packageWindows
            |
            |Installers will be in: build/installers/
            """.trimMargin())
    }
}

tasks.register<Exec>("buildHybrid") {
    group = "distribution"
    description = "Build hybrid distribution (JAR + native validators) for current platform"

    dependsOn(":cli:shadowJar")

    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch")

    val platform = when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") -> "linux"
        os.contains("windows") -> "windows"
        else -> "unknown"
    }

    val archName = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
        arch.contains("amd64") || arch.contains("x86_64") -> "x64"
        else -> "unknown"
    }

    val platformFull = "$platform-$archName"

    commandLine("bash", "${projectDir}/tools/scripts/package-hybrid.sh", "$version", platformFull)

    doFirst {
        println("Building hybrid distribution for platform: $platformFull")
    }

    doLast {
        println("""
            |
            |✓ Hybrid distribution built successfully!
            |
            |Location: build/distributions/karandash-hybrid-$version-$platformFull.tar.gz
            |
            |Test with:
            |  tar -xzf build/distributions/karandash-hybrid-$version-$platformFull.tar.gz
            |  build/distributions/karandash-hybrid-$version-$platformFull/bin/karandash --version
            """.trimMargin())
    }
}

// ── CLI Benchmark Tasks ─────────────────────────────────────────────────────

val osName: String = System.getProperty("os.name").lowercase()
val osArch: String = System.getProperty("os.arch")

val nativeTarget = when {
    (osName.contains("mac") || osName.contains("darwin")) && (osArch.contains("aarch64") || osArch.contains("arm64")) -> "macosArm64"
    (osName.contains("mac") || osName.contains("darwin")) -> "macosX64"
    osName.contains("linux") -> "linuxX64"
    else -> null
}

if (nativeTarget != null) {
    val nativeBinaryPath = "cli/build/bin/$nativeTarget/releaseExecutable/cli.kexe"

    tasks.register<Exec>("benchmarkCli") {
        group = "verification"
        description = "Run CLI benchmarks (startup + validation) against native binary"

        dependsOn(":cli:linkReleaseExecutable${nativeTarget.replaceFirstChar { it.uppercase() }}")

        val iterations = project.findProperty("bench.iterations")?.toString() ?: "30"
        val variant = project.findProperty("bench.variant")?.toString() ?: nativeTarget

        commandLine("bash", "${projectDir}/tools/benchmarks/benchmark-cli.sh",
            "${projectDir}/$nativeBinaryPath", iterations, variant)

        doFirst {
            println("Running CLI benchmark: $nativeTarget ($iterations iterations)")
        }
    }

    tasks.register<Exec>("benchmarkCheck") {
        group = "verification"
        description = "Quick regression check — fails build if CLI performance degrades"

        dependsOn(":cli:linkReleaseExecutable${nativeTarget.replaceFirstChar { it.uppercase() }}")

        val iterations = project.findProperty("bench.check.iterations")?.toString() ?: "5"
        // Thresholds come from tools/benchmarks/baseline-<host>.json unless both properties are set.
        val overrides = listOfNotNull(
            project.findProperty("bench.threshold.startup")?.toString(),
            project.findProperty("bench.threshold.validate")?.toString(),
        ).takeIf { it.size == 2 }.orEmpty()

        commandLine(listOf("bash", "${projectDir}/tools/benchmarks/benchmark-regression-check.sh",
            "${projectDir}/$nativeBinaryPath", iterations) + overrides)

        doFirst {
            println("Running benchmark regression check ($iterations iterations)")
        }
    }
}

// ── Kover: Aggregate Coverage Report ─────────────────────────────────────────

kover {
    merge {
        // Container projects (no build file) only group modules and carry no code.
        allProjects { it.buildFile.exists() }
    }
    reports {
        filters {
            excludes {
                // ANTLR-generated parser in dsl:kts-parser (excluded there too).
                packages("io.karandash.parser.generated")
            }
        }
        total {
            xml {
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            html {
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }
        }
    }
}

tasks.register("buildHybridAll") {
    group = "distribution"
    description = "Build hybrid distribution for all platforms (requires cross-compilation setup)"

    dependsOn(":cli:shadowJar")

    doLast {
        println("""
            |
            |Note: Building hybrid distributions for all platforms:
            |  - macOS ARM64: ./tools/scripts/package-hybrid.sh $version macos-arm64
            |  - macOS x64:   ./tools/scripts/package-hybrid.sh $version macos-x64
            |  - Linux x64:   ./tools/scripts/package-hybrid.sh $version linux-x64
            |  - Windows x64: ./tools/scripts/package-hybrid.sh $version windows-x64
            |
            |Or use CI/CD workflow for multi-platform builds.
            """.trimMargin())
    }
}
