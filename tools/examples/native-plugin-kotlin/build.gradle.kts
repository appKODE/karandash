plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        binaries {
            sharedLib {
                baseName = "kotlin-example"
            }
        }
    }

    linuxX64 {
        binaries {
            sharedLib {
                baseName = "kotlin-example"
            }
        }
    }

    sourceSets {
        val commonMain by getting

        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val macosArm64Main by getting {
            dependsOn(nativeMain)
        }

        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
    }
}
