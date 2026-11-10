plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
        }
        jvmMain.dependencies {
            implementation(libs.chicory.runtime)
        }
        // Chasm (pure Kotlin) is the engine on every native target. Chasm 2.x dropped macosX64, which stays on
        // Chasm 1.7.0, the last release with a macosX64 build (src/macosX64Main).
        val chasmMain by creating {
            dependsOn(nativeMain.get())
            dependencies { implementation(libs.chasm) }
        }
        listOf("macosArm64Main", "linuxX64Main", "mingwX64Main").forEach { findByName(it)?.dependsOn(chasmMain) }
        findByName("macosX64Main")?.dependencies { implementation(libs.chasm.macosx64) }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

detekt {
    source.from("src/chasmMain/kotlin", "src/macosX64Main/kotlin")
}
