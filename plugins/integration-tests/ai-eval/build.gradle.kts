// AI eval harness: replays recorded LLM responses for a labelled MR corpus through every
// AI analyzer, scores precision/recall/F1 and fails `jvmTest` when an analyzer drops below
// `src/commonTest/resources/eval/baseline.json`. Live mode: see docs/development/ai-eval.md.
plugins {
    id("karandash.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

val analyzers = listOf(
    "change-categorization",
    "code-quality",
    "code-suggestions",
    "comment-analysis",
    "description-quality",
    "design-doc-drift",
    "fix-suggestions",
    "generate-description",
    "mr-summary",
    "outdated-docs",
    "ownership-analysis",
    "pr-split",
    "review-comments",
    "risk-scoring",
    "similar-issues",
)

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(project(":core:api"))
            implementation(project(":core:models"))
            implementation(project(":core:platform"))
            implementation(project(":plugins:sdk"))
            implementation(project(":plugins:plugins-ai:ai-core"))
            implementation(project(":plugins:plugins-ai:providers"))
            implementation(project(":test-fixtures"))
            // Full-pipeline replay drives the real `validate` pipeline (cli -> builtin modules).
            implementation(project(":cli"))
            implementation(project(":core:logger"))
            implementation(project(":core:validation"))
            implementation(project(":core:state"))
            implementation(project(":plugins:bootstrap"))
            implementation(project(":plugins:plugins-core:outputs:platform-output"))
            implementation(project(":plugins:loader"))
            analyzers.forEach { implementation(project(":plugins:plugins-ai:analyzers:$it")) }
        }
    }
}

tasks.named<Test>("jvmTest") {
    // Live runs hit a real provider: never serve them from the build cache.
    val live = providers.environmentVariable("KARANDASH_EVAL_LIVE").orElse("")
    inputs.property("evalLive", live)
    outputs.upToDateWhen { live.get().isEmpty() }
    // -Dkarandash.updateGolden=true rewrites eval/replay/*.md, -Dkarandash.updateBaseline=true eval/baseline.json.
    listOf("karandash.updateGolden", "karandash.updateBaseline").forEach { key ->
        val value = providers.systemProperty(key).getOrElse("false")
        inputs.property(key, value)
        systemProperty(key, value)
    }
}

// the offline tests of the corpus fetcher that feeds the labelled MR corpus (tools/audit).
tasks.register<Exec>("corpusFetcherTest") {
    group = "verification"
    description = "Run the offline tests of tools/audit/fetch_gitlab_corpus.py (subprocess stubbed)"
    commandLine("python3", "-I", rootProject.file("tools/audit/test_fetch_gitlab_corpus.py").path)
}
