plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.example.karandash"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // The karandash host provides the SDK (and kotlinx-serialization / coroutines) at runtime,
    // so the plugin JAR must not bundle it: compileOnly, not implementation.
    compileOnly("ru.kode.karandash:sdk")

    testImplementation("ru.kode.karandash:sdk")
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}
