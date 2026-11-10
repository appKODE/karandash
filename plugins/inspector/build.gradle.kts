plugins {
    id("karandash.kmp-ktor-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":plugins:loader"))
            implementation(project(":plugins:registry"))
            implementation(project(":core:platform"))
            implementation(project(":core:file"))
            implementation(project(":core:http"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        named("jvmMain") {
            dependencies {
                implementation(project(":plugins:bootstrap"))
            }
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Booby-trapped native library for the static-inspection test (src/nativeTest/c): its constructor creates a
// marker file if the library is ever loaded. Built with the host C compiler for the host's native test task.
val boobyTrapLib = layout.buildDirectory.file(
    if (org.gradle.internal.os.OperatingSystem
            .current()
            .isMacOsX
    ) {
        "booby-trap/libbooby_trap.dylib"
    } else {
        "booby-trap/libbooby_trap.so"
    },
)
val buildBoobyTrapLib by tasks.registering(Exec::class) {
    val source = layout.projectDirectory.file("src/nativeTest/c/booby_trap.c")
    inputs.file(source)
    outputs.file(boobyTrapLib)
    commandLine("cc", "-shared", "-fPIC", "-o", boobyTrapLib.get().asFile.path, source.asFile.path)
    doFirst {
        val onPath =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .any { File(it, "cc").canExecute() }
        check(onPath) { "cc not found on PATH: install gcc/clang — needed for inspector native tests (booby_trap.c)" }
        boobyTrapLib
            .get()
            .asFile.parentFile
            .mkdirs()
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    if (name.startsWith("macos") || name.startsWith("linux")) {
        dependsOn(buildBoobyTrapLib)
        environment("BOOBY_TRAP_LIB", boobyTrapLib.get().asFile.path)
    }
}
