plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core:multipattern"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // write(2) widths differ on mingw; fork(2) does not exist there
        // posixMain itself comes from karandash.kmp-library
        val posixTest by creating { dependsOn(getByName("nativeTest")) }
        listOf("macosArm64Test", "macosX64Test", "linuxX64Test").forEach { findByName(it)?.dependsOn(posixTest) }
    }
}
