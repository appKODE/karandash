plugins {
    id("karandash.kmp-profile-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":plugins:plugins-core:rules:title-format"))
            implementation(project(":plugins:plugins-core:rules:commit-messages"))
            implementation(project(":plugins:plugins-core:rules:size-limits"))
            implementation(project(":plugins:plugins-core:rules:draft"))
        }
    }
}
