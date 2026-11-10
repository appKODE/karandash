import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("karandash.kmp-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.findLibrary("ktor-client-cio").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }

        findByName("macosArm64Main")?.dependencies {
            implementation(libs.findLibrary("ktor-client-curl").get())
        }
        findByName("macosX64Main")?.dependencies {
            implementation(libs.findLibrary("ktor-client-curl").get())
        }
        findByName("linuxX64Main")?.dependencies {
            implementation(libs.findLibrary("ktor-client-curl").get())
        }
        findByName("mingwX64Main")?.dependencies {
            implementation(libs.findLibrary("ktor-client-winhttp").get())
        }
    }
}
