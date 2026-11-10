plugins {
    id("karandash.kmp-library")
    id("karandash.all-rules-modules")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:api"))
            implementation(project(":core:validation"))
            implementation(project(":core:config"))
            implementation(project(":plugins:sdk"))
            implementation(project(":test-fixtures"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":plugins:plugins-core:profiles:minimal"))
            implementation(project(":plugins:plugins-core:profiles:conventional-commits"))
            implementation(project(":plugins:plugins-core:profiles:github-flow"))
            implementation(project(":plugins:plugins-core:profiles:gitflow"))
            implementation(project(":plugins:plugins-core:profiles:integration-mr"))
            implementation(project(":plugins:plugins-core:profiles:trunk-based"))
            implementation(project(":plugins:plugins-core:profiles:semantic-versioning"))
            implementation(project(":plugins:plugins-core:profiles:security-conscious"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:rules:ai"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:helpers"))
            implementation(project(":plugins:plugins-core:formats:markdown-format"))
            implementation(project(":plugins:plugins-core:formats:console-format"))
            implementation(project(":plugins:plugins-extended:formats:html-format"))
            implementation(project(":plugins:plugins-extended:formats:slack-format"))
            implementation(project(":plugins:plugins-extended:outputs:status-output"))
            implementation(project(":plugins:plugins-extended:outputs:email-output"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:plugins-extended:translations:polish"))
            implementation(project(":plugins:plugins-extended:translations:russian"))
        }
    }
}
