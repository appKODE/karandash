package io.karandash.cli.docs

import io.karandash.platforms.awscodecommit.AwsCodeCommitConfig
import io.karandash.platforms.azuredevops.AzureDevOpsConfig
import io.karandash.platforms.bitbucket.BitbucketConfig
import io.karandash.platforms.gerrit.GerritConfig
import io.karandash.platforms.gitea.GiteaConfig
import io.karandash.platforms.github.GitHubConfig
import io.karandash.platforms.gitlab.GitLabConfig
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every key of a rule config (`@Serializable data class *Config` constructor `val`s under
 * `plugins/<tier>/rules`) appears in the docs, so a key added in code without a docs line fails here.
 */
class ConfigKeysDocsTest {
    /** Rule module directory to its docs/rules page(s), where the page name differs from the module name. */
    private companion object {
        val RULE_PAGES = mapOf(
            "approval" to listOf("approvals"),
            "conflict" to listOf("conflict-detection"),
            "context-tag" to listOf("context-tags"),
            "dependencies" to listOf("dependency-validation"),
            "draft" to listOf("draft-detection"),
            "reviewer" to listOf("reviewers"),
            "jira-ticket" to listOf("jira-ticket", "jira-validation"),
        )
    }

    @Test
    fun `every rule config key is on its own rule page`() {
        val missing = ruleConfigFiles().flatMap { file ->
            val ruleDir = file.path.substringAfter("/rules/").substringBefore("/")
            val pages = (RULE_PAGES[ruleDir] ?: listOf(ruleDir)).map { DocsSupport.readOrEmpty("docs/rules/$it.md") }
            assertTrue(pages.all { it.isNotEmpty() }, "no docs/rules page for rule module '$ruleDir'")
            keysOf(file.readText()).filterNot { key -> pages.any { documents(it, key) } }.map { "${file.name}: $it" }
        }
        assertTrue(missing.isEmpty(), "rule config keys missing on their rule page:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `platform config keys are on the platform page`() {
        val platforms = mapOf(
            "gitlab" to listOf(GitLabConfig.serializer().descriptor),
            "github" to listOf(GitHubConfig.serializer().descriptor),
            "bitbucket" to listOf(BitbucketConfig.serializer().descriptor),
            "gitea" to listOf(GiteaConfig.serializer().descriptor),
            "gerrit" to listOf(GerritConfig.serializer().descriptor),
            "azure-devops" to listOf(AzureDevOpsConfig.serializer().descriptor),
            "aws-codecommit" to listOf(AwsCodeCommitConfig.serializer().descriptor),
        )
        val missing = platforms.flatMap { (page, descriptors) ->
            val text = DocsSupport.read("docs/platforms/$page.md")
            descriptors.flatMap { keysOf(it) }.filterNot { documents(text, it) }.map { "$page: $it" }
        }
        assertTrue(missing.isEmpty(), "platform config keys missing on their page:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `format and output option keys are documented`() {
        val docs = markdown("docs")
        val sources = listOf("plugins/plugins-core/formats", "plugins/plugins-core/outputs")
        val files = sources.flatMap { dir ->
            File(DocsSupport.path(dir).toString())
                .walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.path.contains("/commonMain/") && it.extension == "kt" }
                .toList()
        }
        val keys = files.flatMap { keysOf(it.readText()) }
        assertTrue(keys.isNotEmpty(), "found no format/output config keys, the scan is broken")
        val missing = keys.distinct().filterNot { key ->
            // YAML users type kebab-case (`inline-why`), so that spelling counts as documented too
            key in docs || key.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() } in docs
        }
        assertTrue(missing.isEmpty(), "format/output option keys with no docs hit: $missing")
    }

    @Test
    fun `platform pages quote only auth errors the code produces`() {
        val code =
            sourceText("cli/src/commonMain") + sourceText("plugins/plugins-core/platforms") +
                sourceText("plugins/plugins-extended/platforms")
        assertTrue(code.contains("Your token is invalid, expired, or revoked."))
        assertTrue(code.contains("Authentication failed for \$platformName provider (HTTP 401)."))
        val pages = File(DocsSupport.path("docs/platforms").toString()).listFiles { f -> f.extension == "md" }.orEmpty()
        assertTrue(pages.size >= 8, "docs/platforms pages not found")
        pages.forEach { page ->
            val text = page.readText()
            assertTrue(
                !text.contains("providers.") && !text.contains("authentication not configured", ignoreCase = true),
                "${page.name}: stale error text"
            )
            if (text.contains("Authentication failed for")) {
                assertTrue(
                    text.contains("Your token is invalid, expired, or revoked."),
                    "${page.name}: auth error block differs from the code"
                )
                Regex("Hint: configure token via (.+)\\.\n").findAll(text).forEach {
                    assertTrue(
                        code.contains("\"${it.groupValues[1]}\""),
                        "${page.name}: hint '${it.groupValues[1]}' is not an authTokenHint in the code"
                    )
                }
            }
        }
    }

    @Test
    fun `webhook page claims retry only through the shared http client that has it`() {
        val page = DocsSupport.read("docs/reporters/webhook.md")
        val module = sourceText("plugins/plugins-extended/outputs/webhook-output/src/commonMain")
        val factory = sourceText("core/http/src/commonMain")
        val retryBackedByCode = module.contains("ProviderHttpClientFactory") && factory.contains("HttpRequestRetry")
        assertTrue(
            !page.contains("retry") || (retryBackedByCode && page.contains("ProviderHttpClientFactory")),
            "retry claim without backing"
        )
        assertTrue(!page.contains("with retry"), "the module itself has no retry")
    }

    /** The key as the user types it: a backticked token or a `key:` line, in camelCase or kebab-case. */
    private fun documents(
        page: String,
        key: String,
    ): Boolean {
        val kebab = key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
        return setOf(key, kebab).any { Regex("`${Regex.escape(it)}`|(?m)(?:^|[\\s\"'{,-])${Regex.escape(it)}\\??:").containsMatchIn(page) }
    }

    private fun keysOf(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount).flatMap { i ->
            val child = descriptor.getElementDescriptor(i)
            listOf(descriptor.getElementName(i)) + if (child.kind == StructureKind.CLASS) keysOf(child) else emptyList()
        }

    private fun root() = File(DocsSupport.path(".").toString())

    private fun ruleConfigFiles(): List<File> =
        root()
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter {
                it.path.contains("/commonMain/") && it.name.endsWith("Config.kt") &&
                    (it.path.contains("/plugins-core/rules/") || it.path.contains("/plugins-extended/rules/"))
            }.toList()

    private fun keysOf(source: String): List<String> =
        Regex("@Serializable\\s+(?:internal )?data class \\w*Config\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .flatMap { Regex("(?m)^\\s+(?:@SerialName\\(\"([^\"]+)\"\\)\\s+)?val (\\w+):").findAll(it.groupValues[1]) }
            // The key users type is the @SerialName when there is one
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()

    private fun markdown(relative: String): String {
        val f = File(DocsSupport.path(relative).toString())
        return if (f.isFile) f.readText() else f.walkTopDown().filter { it.extension == "md" }.joinToString("\n") { it.readText() }
    }

    private fun sourceText(relative: String): String =
        File(DocsSupport.path(relative).toString())
            .walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.extension == "kt" && !it.path.contains("Test") }
            .joinToString("\n") { it.readText() }
}
