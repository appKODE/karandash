plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.atomicfu)
}

// Matches core/api: no automatic atomicfu dependency injection (avoids duplicate deps on native test source sets).
atomicfu {
    dependenciesVersion = null
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:cache"))
            api(project(":core:common"))
            // GlobExpander takes a kotlinx.io Path
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":test-fixtures"))
            // GlobParityTest freezes TextGlobCompiler next to GlobMatcher's path, CODEOWNERS and wildcard modes
            implementation(project(":core:multipattern"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}
