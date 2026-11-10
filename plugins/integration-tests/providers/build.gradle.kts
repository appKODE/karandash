plugins {
    id("karandash.kmp-library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":plugins:sdk"))
            implementation(project(":test-fixtures"))
            // Consolidated platform service modules
            implementation(project(":plugins:plugins-core:platforms:gitlab"))
            implementation(project(":plugins:plugins-core:platforms:github"))
            implementation(project(":plugins:plugins-core:platforms:bitbucket"))
            implementation(project(":plugins:plugins-extended:platforms:azuredevops"))
            implementation(project(":plugins:plugins-extended:platforms:gerrit"))
            implementation(project(":plugins:plugins-extended:platforms:gitea"))
            implementation(project(":plugins:plugins-extended:platforms:awscodecommit"))
            // Inline comment lifecycle scenarios: the real GitLab service over a stateful MockEngine fake.
            implementation(libs.ktor.client.mock)
            implementation(project(":core:http"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            // AI provider contract: stream == non-stream, and the opt-in stub provider
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:plugins-extended:ai:stub-provider"))
            implementation(libs.kotlinx.io.core)
            implementation(project(":core:common"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
        }
    }
}
