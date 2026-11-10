plugins {
    id("karandash.jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:cache"))
    implementation(project(":core:common"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:platform"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
    testImplementation(project(":test-fixtures"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    // interop test: the real client fetcher (KtorRemoteCacheFetcher) lives in :cli
    testImplementation(project(":cli"))
}

tasks.shadowJar {
    archiveBaseName.set("karandash-cache-server")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.karandash.cacheserver.MainKt"
    }
}
