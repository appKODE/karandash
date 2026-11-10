@file:Suppress("UnstableApiUsage")

rootProject.name = "karandash"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// ── Core infrastructure ──
include(":core:api")
include(":core:validation")
include(":core:ai-pipeline")
include(":core:file")
include(":core:state")
include(":core:logger")
include(":core:multipattern")
include(":core:common")
include(":core:normalize")
include(":core:merge")
include(":core:models")
include(":core:crypto")
include(":core:download")
include(":core:wasm")
include(":core:config")
include(":core:config-pipeline")
include(":core:cache")
include(":core:platform")
include(":core:glob")
include(":core:http")
include(":core:regex-safety")
include(":core:interpolation")
include(":core:manifest")
include(":core:conditions")
include(":core:detect")
include(":core:crash-handler")
include(":core:watcher")
include(":core:diagnostics")

// ── Shared plugin infrastructure ──
include(":plugins:sdk")
include(":plugins:helpers")
include(":plugins:loader")
include(":plugins:loader:jar-bridge")
include(":plugins:inspector")
// Common platform clients removed - absorbed into platforms/ modules

// ── DSL ──
include(":dsl:kts-parser")
include(":dsl:lsp-server")

// ── CLI ──
include(":cli")
include(":cli-ai")
include(":cli-app")

// ── Tier 1: Core plugins ──
include(":plugins:plugins-core:rules:core")
include(":plugins:plugins-core:rules:title-format")
include(":plugins:plugins-core:rules:commit-messages")
include(":plugins:plugins-core:rules:commit-body")
include(":plugins:plugins-core:rules:branch-naming")
include(":plugins:plugins-core:rules:draft")
include(":plugins:plugins-core:rules:size-limits")
include(":plugins:plugins-core:rules:file-changes")
include(":plugins:plugins-core:rules:labels")
include(":plugins:plugins-core:rules:security")
include(":plugins:plugins-core:platforms:gitlab")
include(":plugins:plugins-core:platforms:github")
include(":plugins:plugins-core:platforms:bitbucket")
include(":plugins:plugins-core:platforms:local-git")
include(":plugins:plugins-core:formats:console-format")
include(":plugins:plugins-core:formats:markdown-format")
include(":plugins:plugins-core:outputs:stdout")
include(":plugins:plugins-core:outputs:platform-output")
include(":plugins:plugins-core:outputs:filesystem")
include(":plugins:plugins-core:parsers:yaml")
include(":plugins:plugins-core:parsers:json")
include(":plugins:plugins-core:parsers:kts")
include(":plugins:plugins-core:profiles:minimal")

// ── Tier 2: Extended plugins ──
include(":plugins:plugins-extended:rules:jira-ticket")
include(":plugins:plugins-extended:rules:codeowners")
include(":plugins:plugins-extended:rules:approval")
include(":plugins:plugins-extended:rules:reviewer")
include(":plugins:plugins-extended:rules:cicd-status")
include(":plugins:plugins-extended:rules:conflict")
include(":plugins:plugins-extended:rules:description-format")
include(":plugins:plugins-extended:rules:context-tag")
include(":plugins:plugins-extended:rules:cross-entity-consistency")
include(":plugins:plugins-extended:rules:language-policy")
include(":plugins:plugins-extended:rules:dependencies")
include(":plugins:plugins-extended:platforms:azuredevops")
include(":plugins:plugins-extended:platforms:gerrit")
include(":plugins:plugins-extended:platforms:gitea")
include(":plugins:plugins-extended:platforms:awscodecommit")
include(":plugins:plugins-extended:platforms:jenkins")
// Old provider/mutator/comment-poster modules removed - absorbed into platforms/ modules
include(":plugins:plugins-extended:enrichment:jira-enrichment")
include(":plugins:plugins-extended:enrichment:confluence-enrichment")
include(":plugins:plugins-extended:enrichment:github-issue-enrichment")
include(":plugins:plugins-extended:enrichment:gitlab-issue-enrichment")
include(":plugins:plugins-extended:enrichment:ci-enrichment")
include(":plugins:plugins-extended:enrichment:symbol-context-enrichment")
include(":plugins:plugins-extended:enrichment:local-git-enrichment")
include(":plugins:plugins-extended:ai:stub-provider")
include(":plugins:plugins-extended:translations:polish")
include(":plugins:plugins-extended:translations:russian")
include(":plugins:plugins-extended:formats:json-format")
include(":plugins:plugins-extended:formats:sarif-format")
include(":plugins:plugins-extended:formats:junit-format")
include(":plugins:plugins-extended:formats:rdjson-format")
include(":plugins:plugins-extended:formats:rdjsonl-format")
include(":plugins:plugins-extended:formats:slack-format")
include(":plugins:plugins-extended:formats:html-format")
include(":plugins:plugins-extended:formats:template-format")
include(":plugins:plugins-extended:outputs:webhook-output")
include(":plugins:plugins-extended:outputs:slack-output")
include(":plugins:plugins-extended:formats:codequality-format")
include(":plugins:plugins-extended:outputs:status-output")
include(":plugins:plugins-extended:outputs:email-output")

include(":plugins:plugins-core:profiles:conventional-commits")
include(":plugins:plugins-core:extensions:severity-cap")
include(":plugins:plugins-core:profiles:github-flow")
include(":plugins:plugins-core:profiles:gitflow")
include(":plugins:plugins-core:profiles:trunk-based")
include(":plugins:plugins-core:profiles:semantic-versioning")
include(":plugins:plugins-core:profiles:security-conscious")
include(":plugins:plugins-core:profiles:integration-mr")
include(":plugins:plugins-extended:scripts")

// ── Tier 3: AI plugins ──
include(":plugins:plugins-ai:ai-core")
include(":plugins:plugins-ai:analyzers:risk-scoring")
include(":plugins:plugins-ai:analyzers:description-quality")
include(":plugins:plugins-ai:analyzers:change-categorization")
include(":plugins:plugins-ai:analyzers:review-comments")
include(":plugins:plugins-ai:analyzers:similar-issues")
include(":plugins:plugins-ai:analyzers:fix-suggestions")
include(":plugins:plugins-ai:analyzers:code-suggestions")
include(":plugins:plugins-ai:analyzers:design-doc-drift")
include(":plugins:plugins-ai:analyzers:code-quality")
include(":plugins:plugins-ai:analyzers:ownership-analysis")
include(":plugins:plugins-ai:analyzers:pr-split")
include(":plugins:plugins-ai:analyzers:generate-description")
include(":plugins:plugins-ai:analyzers:outdated-docs")
include(":plugins:plugins-ai:analyzers:comment-analysis")
include(":plugins:plugins-ai:analyzers:mr-summary")
include(":plugins:plugins-ai:providers")
include(":plugins:plugins-ai:prompts")
include(":plugins:plugins-ai:rules:ai")

// ── Integration tests (not shipped) ──
include(":plugins:integration-tests:rules")
include(":plugins:integration-tests:providers")
include(":plugins:integration-tests:contracts")
include(":plugins:integration-tests:ai-eval")

// ── Plugin bootstrap, registry, and built-in module registration ──
include(":plugins:builtin")
include(":plugins:bootstrap")
include(":plugins:registry")

// ── Test fixtures ──
include(":test-fixtures")

// ── Custom detekt rules (loaded by every detekt task via karandash.lint) ──
include(":tools:detekt-rules")

// ── Self-hosted remote cache server (JVM app, own Docker image) ──
include(":tools:cache-server")

// ── Project configs (integration tests for client validation rules) ──
include(":projects:base")
include(":projects:base-kmp")
include(":projects:navigator")
include(":projects:wallet")
include(":projects:telecom")
include(":projects:coinbox")
include(":projects:ledger")
include(":projects:gridmon")
