plugins {
    id("karandash.kmp-ktor-library")
    id("karandash.all-rules-modules")
    alias(libs.plugins.kotlin.serialization)
}

val generatedDir = layout.buildDirectory.dir("generated-src/builtin-registry/commonMain/kotlin")

val generateBuiltInRegistry by tasks.registering(GenerateBuiltInRegistryTask::class) {
    manifestFile.set(file("builtin-modules.txt"))
    outputDir.set(generatedDir)
    packageName.set("io.karandash.builtin")
    objectName.set("BuiltInModuleRegistry")
    visibility.set("public")
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuiltInRegistry.map { it.outputDir })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.io.core)
            api(project(":core:api"))
            api(project(":plugins:sdk"))
            implementation(project(":plugins:helpers"))

            // --- All built-in plugin modules (hidden behind implementation) ---

            // Shared utilities and plugin loader
            implementation(project(":plugins:loader"))

            // AI modules
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":plugins:plugins-ai:prompts"))
            implementation(project(":plugins:plugins-ai:rules:ai"))
            implementation(project(":plugins:plugins-ai:analyzers:risk-scoring"))
            implementation(project(":plugins:plugins-ai:analyzers:description-quality"))
            implementation(project(":plugins:plugins-ai:analyzers:change-categorization"))
            implementation(project(":plugins:plugins-ai:analyzers:review-comments"))
            implementation(project(":plugins:plugins-ai:analyzers:similar-issues"))
            implementation(project(":plugins:plugins-ai:analyzers:fix-suggestions"))
            implementation(project(":plugins:plugins-ai:analyzers:code-suggestions"))
            implementation(project(":plugins:plugins-ai:analyzers:pr-split"))
            implementation(project(":plugins:plugins-ai:analyzers:generate-description"))
            implementation(project(":plugins:plugins-ai:analyzers:outdated-docs"))
            implementation(project(":plugins:plugins-ai:analyzers:comment-analysis"))
            implementation(project(":plugins:plugins-ai:analyzers:mr-summary"))
            implementation(project(":plugins:plugins-ai:analyzers:design-doc-drift"))
            implementation(project(":plugins:plugins-ai:analyzers:code-quality"))
            implementation(project(":plugins:plugins-ai:analyzers:ownership-analysis"))

            // Additional rule modules (beyond all-rules-modules convention)
            implementation(project(":plugins:plugins-core:rules:commit-body"))
            implementation(project(":plugins:plugins-extended:rules:dependencies"))

            // Platform service modules (unified provider + mutator + comment-poster)
            implementation(project(":plugins:plugins-core:platforms:gitlab"))
            implementation(project(":plugins:plugins-core:platforms:github"))
            implementation(project(":plugins:plugins-core:platforms:bitbucket"))
            implementation(project(":plugins:plugins-core:platforms:local-git"))
            implementation(project(":plugins:plugins-extended:platforms:azuredevops"))
            implementation(project(":plugins:plugins-extended:platforms:gerrit"))
            implementation(project(":plugins:plugins-extended:platforms:gitea"))
            implementation(project(":plugins:plugins-extended:platforms:awscodecommit"))
            implementation(project(":plugins:plugins-extended:platforms:jenkins"))

            // Enrichment providers
            implementation(project(":plugins:plugins-extended:enrichment:jira-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:local-git-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:confluence-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:github-issue-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:gitlab-issue-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:ci-enrichment"))
            implementation(project(":plugins:plugins-extended:enrichment:symbol-context-enrichment"))

            // Opt-in AI provider (`ai.provider: stub`)
            implementation(project(":plugins:plugins-extended:ai:stub-provider"))

            // Profile modules
            implementation(project(":plugins:plugins-core:profiles:minimal"))
            implementation(project(":plugins:plugins-core:profiles:conventional-commits"))
            implementation(project(":plugins:plugins-core:profiles:github-flow"))
            implementation(project(":plugins:plugins-core:profiles:gitflow"))
            implementation(project(":plugins:plugins-core:profiles:trunk-based"))
            implementation(project(":plugins:plugins-core:profiles:semantic-versioning"))
            implementation(project(":plugins:plugins-core:profiles:security-conscious"))
            implementation(project(":plugins:plugins-core:profiles:integration-mr"))

            // Pipeline extensions
            implementation(project(":plugins:plugins-core:extensions:severity-cap"))

            // Format modules
            implementation(project(":plugins:plugins-core:formats:console-format"))
            implementation(project(":plugins:plugins-core:formats:markdown-format"))
            implementation(project(":plugins:plugins-extended:formats:json-format"))
            implementation(project(":plugins:plugins-extended:formats:sarif-format"))
            implementation(project(":plugins:plugins-extended:formats:junit-format"))
            implementation(project(":plugins:plugins-extended:formats:rdjson-format"))
            implementation(project(":plugins:plugins-extended:formats:rdjsonl-format"))
            implementation(project(":plugins:plugins-extended:formats:slack-format"))
            implementation(project(":plugins:plugins-extended:formats:html-format"))
            implementation(project(":plugins:plugins-extended:formats:template-format"))

            // Output modules
            implementation(project(":plugins:plugins-core:outputs:stdout"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:plugins-core:outputs:filesystem"))
            implementation(project(":plugins:plugins-extended:outputs:webhook-output"))
            implementation(project(":plugins:plugins-extended:outputs:slack-output"))
            implementation(project(":plugins:plugins-extended:formats:codequality-format"))
            implementation(project(":plugins:plugins-extended:outputs:status-output"))
            implementation(project(":plugins:plugins-extended:outputs:email-output"))

            // Translation packs
            implementation(project(":plugins:plugins-extended:translations:polish"))
            implementation(project(":plugins:plugins-extended:translations:russian"))

            // Script and parser modules
            implementation(project(":plugins:plugins-extended:scripts"))
            implementation(project(":plugins:plugins-core:parsers:yaml"))
            implementation(project(":plugins:plugins-core:parsers:json"))
            implementation(project(":plugins:plugins-core:parsers:kts"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
