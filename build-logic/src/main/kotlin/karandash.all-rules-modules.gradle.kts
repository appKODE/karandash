plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":plugins:plugins-core:rules:core"))
            implementation(project(":plugins:plugins-core:rules:title-format"))
            implementation(project(":plugins:plugins-core:rules:commit-messages"))
            implementation(project(":plugins:plugins-extended:rules:jira-ticket"))
            implementation(project(":plugins:plugins-core:rules:branch-naming"))
            implementation(project(":plugins:plugins-core:rules:file-changes"))
            implementation(project(":plugins:plugins-extended:rules:codeowners"))
            implementation(project(":plugins:plugins-extended:rules:approval"))
            implementation(project(":plugins:plugins-extended:rules:reviewer"))
            implementation(project(":plugins:plugins-extended:rules:cicd-status"))
            implementation(project(":plugins:plugins-extended:rules:conflict"))
            implementation(project(":plugins:plugins-core:rules:draft"))
            implementation(project(":plugins:plugins-core:rules:labels"))
            implementation(project(":plugins:plugins-core:rules:size-limits"))
            implementation(project(":plugins:plugins-core:rules:security"))
            implementation(project(":plugins:plugins-ai:rules:ai"))
            implementation(project(":plugins:plugins-extended:rules:context-tag"))
            implementation(project(":plugins:plugins-extended:rules:description-format"))
            implementation(project(":plugins:plugins-core:rules:commit-body"))
            implementation(project(":plugins:plugins-extended:rules:cross-entity-consistency"))
            implementation(project(":plugins:plugins-extended:rules:language-policy"))
            implementation(project(":plugins:plugins-extended:rules:dependencies"))
        }
    }
}
