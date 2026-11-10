plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core:api"))
    implementation(project(":plugins:sdk"))
    implementation(project(":plugins:loader"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    testImplementation(kotlin("test"))
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("karandash-jar-bridge")
    archiveClassifier.set("")
    // Fixed name: native hosts look for `karandash-jar-bridge.jar` next to the binary (JarBridgeClient.BRIDGE_JAR_NAME).
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.karandash.loader.jar.JarBridgeServer"
    }
    // No minimize(): plugin JARs run on this classpath and extend SDK classes the bridge itself never
    // references (AbstractRuleModule, ...), so stripping unreferenced classes breaks plugin loading.
}

kotlin {
    jvmToolchain(17)
}
