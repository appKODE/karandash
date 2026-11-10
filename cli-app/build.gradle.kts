plugins {
    id("karandash.jvm-application")
    application
}

application {
    mainClass.set("io.karandash.cliapp.MainKt")
}

// Transitive dependencies can pull the same JAR via different module paths.
// Exclude duplicates to avoid packaging failures.
tasks.withType<Tar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<Zip> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

tasks.shadowJar {
    archiveBaseName.set("karandash")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "io.karandash.cliapp.MainKt"
    }
}

dependencies {
    implementation(project(":cli"))
    // `runCli` takes a `(PluginBootstrap) -> Unit` language-server hook
    implementation(project(":plugins:bootstrap"))
    implementation(project(":core:api"))
    implementation(project(":core:platform"))
    implementation(project(":core:crash-handler"))
    // `karandash lsp`: JVM-only, so bundled here rather than in the multiplatform :cli
    implementation(project(":dsl:lsp-server"))
    testImplementation(kotlin("test"))
}

tasks.register<Exec>("nativeImage") {
    group = "build"
    description = "Build GraalVM native image (requires GraalVM with native-image installed)"

    dependsOn("shadowJar")

    val jarFile = layout.buildDirectory
        .file("libs/karandash.jar")
        .get()
        .asFile
    val outputBinary = layout.buildDirectory
        .file("native/karandash")
        .get()
        .asFile

    doFirst {
        outputBinary.mkdirs()
        if (!jarFile.exists()) {
            throw GradleException("CLI shadow JAR not found at ${jarFile.absolutePath}")
        }

        val nativeImagePath = System.getenv("GRAALVM_HOME")?.let { "$it/bin/native-image" }
            ?: "native-image"

        try {
            val process = ProcessBuilder(nativeImagePath, "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.close()
            if (process.waitFor() != 0) {
                throw IllegalStateException("native-image --version exited with code ${process.exitValue()}")
            }
        } catch (_: Exception) {
            throw GradleException(
                """
                native-image not found. Please install GraalVM and native-image:
                1. Download GraalVM from https://www.graalvm.org/downloads/
                2. Set GRAALVM_HOME environment variable
                3. Install native-image: gu install native-image
                """.trimIndent(),
            )
        }
    }

    commandLine(
        System.getenv("GRAALVM_HOME")?.let { "$it/bin/native-image" } ?: "native-image",
        "-jar",
        jarFile.absolutePath,
        "-o",
        outputBinary.absolutePath,
        "--no-fallback",
        "--enable-url-protocols=http,https",
        "--enable-all-security-services",
        "-H:+ReportExceptionStackTraces",
        "-H:+AddAllCharsets",
        "--initialize-at-build-time=org.slf4j",
        "--initialize-at-build-time=kotlinx.serialization",
        "--initialize-at-build-time=kotlin",
        "--initialize-at-run-time=io.ktor.client.engine.okhttp.OkHttpEngine",
        "--initialize-at-run-time=io.ktor.client.engine.cio.CIOEngine",
        "-H:ReflectionConfigurationFiles=$projectDir/src/main/resources/META-INF/native-image/io.karandash.cli/reflect-config.json",
        "-H:ResourceConfigurationFiles=$projectDir/src/main/resources/META-INF/native-image/io.karandash.cli/resource-config.json",
        "-H:SerializationConfigurationFiles=$projectDir/src/main/resources/META-INF/native-image/io.karandash.cli/serialization-config.json",
        "-H:JNIConfigurationFiles=$projectDir/src/main/resources/META-INF/native-image/io.karandash.cli/jni-config.json",
        "--verbose",
    )

    doLast {
        println("Native image built successfully: ${outputBinary.absolutePath}")
        println("Binary size: ${outputBinary.length() / 1024 / 1024} MB")
        println("Test with: $outputBinary --version")
    }
}

tasks.register<Exec>("nativeImageTest") {
    group = "verification"
    description = "Test the native image binary"

    dependsOn("nativeImage")

    val outputBinary = layout.buildDirectory
        .file("native/karandash")
        .get()
        .asFile

    commandLine(outputBinary.absolutePath, "--version")

    doLast {
        println("Native image test passed")
    }
}
