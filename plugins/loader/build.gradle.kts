plugins {
    id("karandash.kmp-ktor-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.quickjs.kt)
            implementation(project(":core:api"))
            implementation(project(":core:cache"))
            implementation(project(":core:crypto"))
            implementation(project(":core:download"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:wasm"))
            implementation(project(":core:common"))
            implementation(project(":core:platform"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.chicory.runtime)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            implementation(project(":test-fixtures"))
        }
        named("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        if (name.contains("Test")) {
            freeCompilerArgs.add("-Xsuppress-version-warnings")
        }
    }
}

// Tiny C CLI_COMMAND plugins for the native loader lifecycle test (src/nativeTest/c), built with the host C compiler
// for the host's native test task: one whose initialize succeeds, one whose initialize refuses.
val lifecyclePluginDir = layout.buildDirectory.dir("lifecycle-plugins")
val lifecyclePluginExt = if (org.gradle.internal.os.OperatingSystem
        .current()
        .isMacOsX
) {
    "dylib"
} else {
    "so"
}
val buildLifecyclePlugins = mapOf("native-ok" to 1, "native-refuses" to 0).map { (id, initResult) ->
    tasks.register<Exec>("buildLifecyclePlugin-$id") {
        val source = layout.projectDirectory.file("src/nativeTest/c/lifecycle_plugin.c")
        val lib = lifecyclePluginDir.get().file("lib$id.$lifecyclePluginExt").asFile
        inputs.file(source)
        outputs.file(lib)
        doFirst { lib.parentFile.mkdirs() }
        commandLine("cc", "-shared", "-fPIC", "-DPLUGIN_ID=\"$id\"", "-DINIT_RESULT=$initResult", "-o", lib.path, source.asFile.path)
    }
}
// ABI v2 host-channel plugin (src/nativeTest/c/host_plugin.c) for the native and the JNA host-channel tests.
val buildHostPlugin = tasks.register<Exec>("buildHostPlugin") {
    val source = layout.projectDirectory.file("src/nativeTest/c/host_plugin.c")
    val lib = lifecyclePluginDir.get().file("libnative-host.$lifecyclePluginExt").asFile
    inputs.file(source)
    outputs.file(lib)
    doFirst { lib.parentFile.mkdirs() }
    // universal on macOS: the test JVM may run as x86_64 (Rosetta) while the native test binary is arm64
    val arch = if (lifecyclePluginExt == "dylib") listOf("-arch", "arm64", "-arch", "x86_64") else emptyList()
    commandLine(listOf("cc", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror") + arch + listOf("-o", lib.path, source.asFile.path))
}
if (!org.gradle.internal.os.OperatingSystem
        .current()
        .isWindows
) {
    tasks.named<Test>("jvmTest") {
        dependsOn(buildHostPlugin)
        environment("LIFECYCLE_PLUGIN_DIR", lifecyclePluginDir.get().asFile.path)
    }
}
// ABI compile test: src/nativeTest/c/ops_plugin.c must compile warning-free against the generated header.
val buildOpsPlugin = tasks.register<Exec>("buildOpsPlugin") {
    val source = layout.projectDirectory.file("src/nativeTest/c/ops_plugin.c")
    val header = rootProject.layout.projectDirectory.file("docs/native-sdk/karandash_plugin.h")
    val lib = lifecyclePluginDir.get().file("libops.$lifecyclePluginExt").asFile
    inputs.files(source, header)
    outputs.file(lib)
    doFirst { lib.parentFile.mkdirs() }
    commandLine(
        "cc",
        "-shared",
        "-fPIC",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wmissing-prototypes",
        "-I",
        header.asFile.parentFile.path,
        "-o",
        lib.path,
        source.asFile.path,
    )
}
// WASM examples are build outputs: never committed, rebuilt from source by each example's build.sh (clang/emcc for C,
// cargo for Rust; docker/Dockerfile.base installs both). Tests that need the binaries live in `wasmExamplesTest`, so plain
// `jvmTest` runs on a machine without the wasm toolchain; a missing tool fails that task with the build.sh message.
val exampleWasmDir = layout.buildDirectory.dir("example-wasm")
val buildExampleWasm = listOf("standalone-wasm-c-plugin", "standalone-wasm-rust-plugin").map { example ->
    tasks.register<Exec>("buildExampleWasm-$example") {
        val sourceDir = rootProject.layout.projectDirectory.dir("tools/examples/$example")
        val out = exampleWasmDir.get().dir(example)
        inputs.files(sourceDir.asFileTree.matching { exclude("target/**", "plugin.wasm") })
        outputs.file(out.file("plugin.wasm"))
        workingDir = sourceDir.asFile
        environment("OUT_DIR", out.asFile.path)
        commandLine("bash", "build.sh")
    }
}
// The multi-module examples build in place (their build.sh writes next to the sources, git-ignored); the integration
// scripts (tools/integration-tests/multi-language-plugin-test.sh) call these tasks instead of build.sh.
listOf("wasm-plugin-c", "wasm-plugin-rust").forEach { example ->
    tasks.register<Exec>("buildExampleWasm-$example") {
        val sourceDir = rootProject.layout.projectDirectory.dir("tools/examples/$example")
        inputs.files(sourceDir.asFileTree.matching { exclude("target/**", "**/*.wasm") })
        // wasm-plugin-rust's rule crate shares models with native-plugin-rust through this path dependency.
        inputs.files(
            rootProject.layout.projectDirectory.dir("tools/examples/karandash-plugin-common").asFileTree.matching {
                exclude("target/**")
            },
        )
        outputs.files(sourceDir.asFileTree.matching { include("*.wasm") })
        workingDir = sourceDir.asFile
        commandLine("bash", "build.sh")
    }
}
val wasmToolchainTests = listOf("*StandaloneWasmExamplesTest", "*WasmPluginLoaderLifecycleTest")
tasks.named<Test>("jvmTest") {
    wasmToolchainTests.forEach { filter.excludeTestsMatching(it) }
}
tasks.register<Test>("wasmExamplesTest") {
    group = "verification"
    description = "Tests that run the example WASM binaries; needs clang or emcc and cargo with wasm32-unknown-unknown"
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    wasmToolchainTests.forEach { filter.includeTestsMatching(it) }
    dependsOn(buildExampleWasm)
    environment("EXAMPLE_WASM_DIR", exampleWasmDir.get().asFile.path)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(buildExampleWasm)
    environment("EXAMPLE_WASM_DIR", exampleWasmDir.get().asFile.path)
    if (name.startsWith("macos") || name.startsWith("linux")) {
        dependsOn(buildLifecyclePlugins, buildOpsPlugin, buildHostPlugin)
        environment("LIFECYCLE_PLUGIN_DIR", lifecyclePluginDir.get().asFile.path)
    }
}
