plugins {
    id("karandash.kmp-library")
    id("karandash.all-rules-modules")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        // real-config-chain test shared by every project
        commonTest { kotlin.srcDir("../shared-tests/src/realChainTest/kotlin") }
        commonTest.dependencies {
            implementation(project(":plugins:sdk"))
            implementation(kotlin("test"))
            implementation(project(":core:api"))
            implementation(project(":test-fixtures"))
            implementation(project(":cli"))
            implementation(project(":core:config-pipeline"))
            implementation(project(":core:interpolation"))
            implementation(project(":core:manifest"))
            implementation(project(":plugins:bootstrap"))
            implementation(project(":plugins:loader"))
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(project(":plugins:plugins-ai:prompts"))
            implementation(project(":plugins:plugins-extended:rules:context-tag"))
            implementation(project(":plugins:plugins-core:rules:commit-body"))
            implementation(project(":plugins:plugins-extended:rules:description-format"))
            implementation(project(":plugins:plugins-extended:rules:cross-entity-consistency"))
            implementation(project(":plugins:plugins-extended:rules:language-policy"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
