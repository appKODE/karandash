plugins {
    id("karandash.jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.lsp4j)
    implementation(libs.antlr.kotlin.runtime)
    implementation(project(":dsl:kts-parser"))
    implementation(project(":core:api"))
    implementation(project(":core:config-pipeline"))
    implementation(project(":core:interpolation"))
    implementation(project(":plugins:sdk"))
    implementation(project(":plugins:bootstrap"))
    implementation(project(":plugins:loader"))
    implementation(project(":cli"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    testImplementation(kotlin("test"))
    testImplementation(project(":test-fixtures"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.shadowJar {
    archiveBaseName.set("karandash-lsp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "io.karandash.lsp.MainKt"
    }
}
