plugins {
    id("karandash.kmp-library")
    id("karandash.publishing")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.atomicfu)
}

// Matches core/api: no automatic atomicfu dependency injection (avoids duplicate deps on native test source sets).
atomicfu {
    dependenciesVersion = null
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:api"))
            api(project(":core:logger"))
            api(project(":core:models"))
            api(project(":core:conditions"))
            api(project(":core:multipattern"))
            api(project(":core:common"))
            // SafeRegex wraps RegexHelper; implementation keeps regex-safety (and core/cache) out of the SDK ABI.
            implementation(project(":core:regex-safety"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("Karandash Plugin SDK")
            description.set("SDK for building Karandash plugins")
        }
    }
}
