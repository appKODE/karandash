import java.io.File
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("karandash.lint")
    id("dev.detekt")
    id("karandash.kover")
}

val hostOs: OperatingSystem = OperatingSystem.current()
val hostArch: String = System.getProperty("os.arch") ?: "unknown"
val isLinuxArm64Host = hostOs.isLinux && hostArch == "aarch64"
val ungatedTargets = project.findProperty("karandash.ungatedTargets")
    ?.toString()?.toBoolean() ?: false
val enableAllNativeTargets = ungatedTargets ||
    project.findProperty("karandash.native.buildAll")?.toString()?.toBoolean() == true ||
    System.getenv("CI")?.toBoolean() == true
// linuxArm64 is opt-in per module (pomsky-kt does not publish it, so it cannot be global)
val linuxArm64Enabled = project.findProperty("karandash.linuxArm64")?.toString()?.toBoolean() ?: false
val includeMacosX64NativeTests = project.providers
    .gradleProperty("karandash.tests.includeMacosX64")
    .map { it.toBoolean() }
    .orElse(false)
    .get()

kotlin {
    jvmToolchain(17)
    jvm()

    if (hostOs.isMacOsX || enableAllNativeTargets) {
        macosArm64()
        macosX64()
    }
    if (hostOs.isLinux || enableAllNativeTargets) {
        linuxX64()
    }
    if (linuxArm64Enabled) {
        linuxArm64()
    }
    if (hostOs.isWindows || enableAllNativeTargets) {
        mingwX64()
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val nativeTest by creating { dependsOn(commonTest.get()) }
        // dlopen/dlsym and write(2) are the same on macOS and Linux, so they share one source set
        val posixMain by creating { dependsOn(nativeMain) }

        if (hostOs.isMacOsX || enableAllNativeTargets) {
            val macosMain by creating { dependsOn(posixMain) }
            macosArm64Main { dependsOn(macosMain) }
            macosX64Main { dependsOn(macosMain) }
        }
        if (hostOs.isLinux || enableAllNativeTargets) {
            val linuxMain by creating { dependsOn(posixMain) }
            findByName("linuxX64Main")?.dependsOn(linuxMain)
            findByName("linuxArm64Main")?.dependsOn(linuxMain)
        }
        if (hostOs.isWindows || enableAllNativeTargets) {
            val mingwMain by creating { dependsOn(nativeMain) }
            mingwX64Main { dependsOn(mingwMain) }
        }

        findByName("macosArm64Test")?.dependsOn(nativeTest)
        findByName("macosX64Test")?.dependsOn(nativeTest)
        findByName("linuxX64Test")?.dependsOn(nativeTest)
        findByName("linuxArm64Test")?.dependsOn(nativeTest)
        findByName("mingwX64Test")?.dependsOn(nativeTest)
    }
}

// the active Xcode (DEVELOPER_DIR, else `xcode-select -p`, else the default install location)
val selectedXcodeDir = if (File("/usr/bin/xcode-select").exists()) {
    providers.exec {
        commandLine("/usr/bin/xcode-select", "-p")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
} else {
    null
}
val swiftLibDirForAppleLinking = listOfNotNull(
    System.getenv("DEVELOPER_DIR"),
    selectedXcodeDir,
    "/Applications/Xcode.app/Contents/Developer",
).map { "$it/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/macosx" }
    .firstOrNull { File(it).isDirectory }

if (swiftLibDirForAppleLinking != null) {
    kotlin {
        targets.withType(KotlinNativeTarget::class.java).configureEach {
            if (konanTarget.family.name == "OSX") {
                binaries.configureEach {
                    linkerOpts("-L$swiftLibDirForAppleLinking")
                }
            }
        }
    }
}

kotlin {
    targets.withType(KotlinNativeTarget::class.java).configureEach {
        if (konanTarget.family.name == "LINUX") {
            binaries.configureEach {
                linkerOpts("--allow-multiple-definition")
            }
        }
        // core/http resolves host names with Winsock getaddrinfo; Kotlin/Native does not link ws2_32 by default
        if (konanTarget.family.name == "MINGW") {
            binaries.configureEach {
                linkerOpts("-lws2_32")
            }
        }
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT,
            org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR,
        )
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Native test timeout: fail fast instead of hanging indefinitely on CI
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    // 10-minute hard limit per native test run; prevents CI stalls caused by
    // blocked syscalls or undrained coroutine dispatchers in K/N binaries
    timeout.set(Duration.ofMinutes(10))
    (TestEnvironment.NATIVE_BLANKED + TestEnvironment.CI_DETECTION).forEach { environment(it, "") }
}

// tests must not auto-detect the CI job's own MR (see TestEnvironment.CI_DETECTION)
tasks.withType<Test>().configureEach {
    environment.keys.removeAll(TestEnvironment.CI_DETECTION.toSet())
}

// -Dkarandash.updateGolden/updateBaseline -> KARANDASH_UPDATE_GOLDEN/BASELINE on every test process (see TestEnvironment)
val updateFlagValues = TestEnvironment.UPDATE_FLAGS.map { (property, env) ->
    env to providers.systemProperty(property).getOrElse("false")
}
tasks.withType<Test>().configureEach {
    updateFlagValues.forEach { (env, value) ->
        environment(env, value)
        inputs.property(env, value)
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    // tracked: flipping a flag reruns the task instead of serving it from the build cache
    updateFlagValues.forEach { (env, value) -> environment(env, value) }
    // KGP leaves native test runs uncacheable; the kexe is an input, and src/ covers the goldens/resources
    // the tests read from disk at run time
    inputs.dir(layout.projectDirectory.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE).optional()
    outputs.cacheIf { true }
}

// Repo files a module's tests read at run time from outside the module (docs, CHANGELOG, tools/examples, fixtures),
// as git glob pathspecs from the repo root in `karandash.testRepoInputs`. Without them a cached test result survives
// an edit to those files. Tracked files only: tests also write ignored state (cache/, .karandash/) under the root.
val testRepoInputs = project.findProperty("karandash.testRepoInputs")?.toString()
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
if (testRepoInputs.isNotEmpty()) {
    val repoRoot = rootDir
    val repoInputs = providers.exec {
        commandLine(listOf("git", "ls-files", "-z", "--") + testRepoInputs.map { ":(glob)$it" })
        workingDir = repoRoot
        isIgnoreExitValue = true
    }.standardOutput.asText.map { out -> out.split('\u0000').filter { it.isNotEmpty() }.map { repoRoot.resolve(it) } }
    tasks.withType<Test>().configureEach {
        inputs.files(repoInputs).withPropertyName("testRepoInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        inputs.files(repoInputs).withPropertyName("testRepoInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

if (hostOs.isMacOsX && !includeMacosX64NativeTests) {
    tasks.matching {
        val taskName = it.name.lowercase()
        taskName.contains("compiletestkotlinmacosx64") ||
            taskName.contains("linkdebugtestmacosx64") ||
            taskName.contains("linkreleasetestmacosx64") ||
            taskName.contains("macosx64test")
    }.configureEach {
        enabled = false
        onlyIf { false }
    }
}

configure<dev.detekt.gradle.extensions.DetektExtension> {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoredBuildTypes = listOf("release")
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/nativeMain/kotlin",
        "src/macosMain/kotlin",
        "src/linuxMain/kotlin",
        "src/mingwMain/kotlin",
        "src/macosArm64Main/kotlin",
        "src/macosX64Main/kotlin",
        "src/linuxX64Main/kotlin",
        "src/linuxArm64Main/kotlin",
        "src/mingwX64Main/kotlin",
        "src/commonTest/kotlin",
        "src/jvmTest/kotlin",
    )
}

// Dependency direction guard: rules and allowlist live in DependencyDirection (unit-tested in build-logic).
afterEvaluate {
    val violations = configurations
        .filterNot { it.name.contains("Test") }
        .flatMap { conf -> conf.dependencies.withType<ProjectDependency>().map { it.path } }
        .distinct()
        .mapNotNull { dep -> DependencyDirection.forbiddenEdge(path, dep)?.let { "$dep ($it)" } }
    if (violations.isNotEmpty()) {
        throw GradleException("$path must not depend on: ${violations.joinToString("; ")}")
    }
}
