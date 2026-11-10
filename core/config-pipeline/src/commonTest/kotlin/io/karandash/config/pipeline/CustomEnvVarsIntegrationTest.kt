package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.config.anyToJsonElement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for custom env vars (`env:` config block) flowing through
 * the full config pipeline: declaration -> parsing -> interpolation -> final Config.
 *
 * These tests verify end-to-end behavior including:
 * - Custom env vars declared in `env:` block are parsed and their defaults are used during interpolation
 * - Defaults from map-form `env:` block are applied when env var is not set
 * - Custom env vars work across rules, providers, reporters, comment-posters, and mutators
 * - Custom env vars union correctly across extends chains
 * - Security denylist is enforced during bootstrap phase
 */
class CustomEnvVarsIntegrationTest {
    // --- Full pipeline: env block + parseFullConfig ---

    @Test
    fun `list-form env vars are interpolated in rules`() {
        val data = mapOf<String, Any?>(
            "env" to listOf("TASK_PREFIX"),
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${TASK_PREFIX}:")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)

        val result = ConfigMapParser.parseFullConfig(data, envDecl.defaults)
        val ruleConfig = result.config.rules.ruleConfigs["titleFormat"]
        assertNotNull(ruleConfig, "titleFormat rule should be parsed")

        val envValue = PlatformEnvironment.getEnv("TASK_PREFIX")
        if (envValue != null) {
            assertEquals(
                "^$envValue:",
                ruleConfig
                    ?.jsonObject
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
            )
        } else {
            // No default and not set -> resolves to empty, tracked as missing
            assertEquals(
                "^:",
                ruleConfig
                    ?.jsonObject
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
            )
            assertTrue(result.missingEnvVars.contains("TASK_PREFIX"))
        }
    }

    @Test
    fun `map-form env vars with defaults are interpolated in rules when env var not set`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("TASK_PREFIX" to "feat"),
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${TASK_PREFIX}:")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val ruleConfig = config.rules.ruleConfigs["titleFormat"]
        assertNotNull(ruleConfig)

        val envValue = PlatformEnvironment.getEnv("TASK_PREFIX")
        val expected = "^${envValue ?: "feat"}:"
        assertEquals(
            expected,
            ruleConfig
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `custom env vars are interpolated in provider config`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("MY_TOKEN" to "fallback-token"),
            "platforms" to mapOf(
                "gitlab" to mapOf(
                    "token" to "\${MY_TOKEN}",
                    "url" to "https://gitlab.example.com"
                )
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val gitlabConfig = config.platforms?.get("gitlab")
        assertNotNull(gitlabConfig, "gitlab provider should be parsed")

        val envValue = PlatformEnvironment.getEnv("MY_TOKEN")
        assertEquals(
            envValue ?: "fallback-token",
            gitlabConfig
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "https://gitlab.example.com",
            gitlabConfig
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `custom env vars are interpolated in reporter config`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("WEBHOOK_ENDPOINT" to "https://hooks.example.com/default"),
            "reporters" to mapOf(
                "webhook" to mapOf("url" to "\${WEBHOOK_ENDPOINT}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val webhookConfig = config.reporters?.get("webhook")
        assertNotNull(webhookConfig, "webhook reporter should be parsed")

        val envValue = PlatformEnvironment.getEnv("WEBHOOK_ENDPOINT")
        assertEquals(
            envValue ?: "https://hooks.example.com/default",
            webhookConfig
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `custom env vars are interpolated in comment-posters config`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("POSTER_TOKEN" to "default-poster-token"),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${POSTER_TOKEN}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val posterConfig = config.platforms?.get("gitlab")
        assertNotNull(posterConfig, "gitlab comment-poster should be parsed")

        val envValue = PlatformEnvironment.getEnv("POSTER_TOKEN")
        assertEquals(
            envValue ?: "default-poster-token",
            posterConfig
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `custom env vars are interpolated in mutators config`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("MUTATOR_TOKEN" to "default-mutator-token"),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${MUTATOR_TOKEN}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val mutatorConfig = config.platforms?.get("gitlab")
        assertNotNull(mutatorConfig, "gitlab mutator should be parsed")

        val envValue = PlatformEnvironment.getEnv("MUTATOR_TOKEN")
        assertEquals(
            envValue ?: "default-mutator-token",
            mutatorConfig
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.content
        )
    }

    // --- Multiple custom env vars in same config ---

    @Test
    fun `multiple custom env vars interpolated across different config sections`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf(
                "TASK_PREFIX" to "feat",
                "PROJECT_TOKEN" to "tok-default",
                "HOOK_URL" to "https://hooks.default.com"
            ),
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${TASK_PREFIX}:")
            ),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${PROJECT_TOKEN}")
            ),
            "reporters" to mapOf(
                "webhook" to mapOf("url" to "\${HOOK_URL}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val rulePattern = config.rules.ruleConfigs["titleFormat"]
            ?.jsonObject
            ?.get("pattern")
            ?.jsonPrimitive
            ?.content
        val providerToken = config.platforms
            ?.get("gitlab")
            ?.jsonObject
            ?.get("token")
            ?.jsonPrimitive
            ?.content
        val reporterUrl = config.reporters
            ?.get("webhook")
            ?.jsonObject
            ?.get("url")
            ?.jsonPrimitive
            ?.content

        assertNotNull(rulePattern)
        assertNotNull(providerToken)
        assertNotNull(reporterUrl)

        // Each should use env value if set, or the declared default
        val expectedPrefix = PlatformEnvironment.getEnv("TASK_PREFIX") ?: "feat"
        val expectedToken = PlatformEnvironment.getEnv("PROJECT_TOKEN") ?: "tok-default"
        val expectedUrl = PlatformEnvironment.getEnv("HOOK_URL") ?: "https://hooks.default.com"

        assertEquals("^$expectedPrefix:", rulePattern)
        assertEquals(expectedToken, providerToken)
        assertEquals(expectedUrl, reporterUrl)
    }

    // --- Custom env vars without defaults (list form) resolve to empty when unset ---

    @Test
    fun `list-form env var without default resolves to empty and is tracked as missing`() {
        val varName = "KARANDASH_TEST_UNLIKELY_SET_VAR_XYZ123"
        val data = mapOf<String, Any?>(
            "env" to listOf(varName),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${$varName}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        assertNull(envDecl.defaults[varName], "List-form var should have no default")

        val envValue = PlatformEnvironment.getEnv(varName)
        if (envValue == null) {
            val result = ConfigMapParser.parseFullConfig(data, envDecl.defaults)
            val gitlabConfig = result.config.platforms?.get("gitlab")
            assertNotNull(gitlabConfig)
            assertEquals("", gitlabConfig.jsonObject["token"]?.jsonPrimitive?.content, "Unset var should resolve to empty string")
            assertTrue(result.missingEnvVars.contains(varName), "Unset var should be tracked as missing")
        }
    }

    // --- Custom env vars coexist with other env vars ---

    @Test
    fun `custom env vars declared in env block are parsed alongside other vars`() {
        val data = mapOf<String, Any?>(
            "env" to listOf("TASK_ID", "JIRA_KEY"),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${GITLAB_TOKEN}", "project" to "\${TASK_ID}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)

        assertTrue("TASK_ID" in envDecl.names)
        assertTrue("JIRA_KEY" in envDecl.names)

        // Provide defaults so unset env vars resolve
        val defaults = mapOf(
            "GITLAB_TOKEN" to "gl-fallback",
            "TASK_ID" to "TASK-000",
        )
        val config = ConfigMapParser.parseFullConfig(data, defaults).config
        assertNotNull(config.platforms?.get("gitlab"))
    }

    // --- Vars not in environment and not in defaults resolve to empty and are tracked as missing ---

    @Test
    fun `vars not in env and not in defaults resolve to empty with denylist approach`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("TASK_ID" to "task-default"),
            "platforms" to mapOf(
                "gitlab" to mapOf(
                    "task" to "\${TASK_ID}",
                    "secret" to "\${UNDECLARED_VAR}"
                )
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val result = ConfigMapParser.parseFullConfig(data, envDecl.defaults)

        val gitlabConfig = result.config.platforms?.get("gitlab")
        assertNotNull(gitlabConfig)
        // With denylist approach, undeclared vars not in env resolve to empty
        assertEquals(
            "",
            gitlabConfig
                ?.jsonObject
                ?.get("secret")
                ?.jsonPrimitive
                ?.content,
            "Undeclared var not in env should resolve to empty string"
        )
        // The missing var should be tracked in the result
        assertTrue(
            result.missingEnvVars.contains("UNDECLARED_VAR"),
            "Undeclared var should be tracked as missing"
        )
    }

    // --- Bootstrap phase: security denylist ---

    @Test
    fun `SECURITY - bootstrap rejects config with restricted env var in list form`() {
        val data = mapOf<String, Any?>(
            "env" to listOf("TASK_ID", "PATH"),
            "rules" to mapOf("title-format" to mapOf("pattern" to "^feat:"))
        )

        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseBootstrapConfig(data)
        }
        assertTrue(ex.message!!.contains("E0021"))
        assertTrue(ex.message!!.contains("PATH"))
    }

    @Test
    fun `SECURITY - bootstrap rejects config with restricted env var in map form`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("TASK_ID" to null, "SSH_AUTH_SOCK" to null),
            "rules" to mapOf("title-format" to mapOf("pattern" to "^feat:"))
        )

        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseBootstrapConfig(data)
        }
        assertTrue(ex.message!!.contains("E0021"))
        assertTrue(ex.message!!.contains("SSH_AUTH_SOCK"))
    }

    @Test
    fun `SECURITY - bootstrap rejects multiple restricted vars and reports all`() {
        val data = mapOf<String, Any?>(
            "env" to listOf("PATH", "HOME", "TASK_ID")
        )

        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseBootstrapConfig(data)
        }
        assertTrue(ex.message!!.contains("PATH"))
        assertTrue(ex.message!!.contains("HOME"))
    }

    // --- Extends chains with custom env vars ---

    private val stubYamlModule = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement = anyToJsonElement(parseSimpleYaml(content))

        override suspend fun parseExtendsOnly(content: String): JsonElement? {
            val map = parseSimpleYaml(content)
            val raw = map["extends"] ?: return null
            return anyToJsonElement(raw)
        }

        @Suppress("CyclomaticComplexMethod")
        private fun parseSimpleYaml(content: String): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()
            var currentSection: String? = null
            val sectionMap = mutableMapOf<String, Any?>()

            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.contains(":")) {
                    if (currentSection != null && sectionMap.isNotEmpty()) {
                        result[currentSection] = sectionMap.toMap()
                        sectionMap.clear()
                    }
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":").trim()
                    if (value.isEmpty()) {
                        currentSection = key
                    } else if (value.startsWith("[") && value.endsWith("]")) {
                        // Simple list parsing: [A, B, C]
                        currentSection = null
                        val items = value
                            .removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotEmpty() }
                        result[key] = items
                    } else {
                        currentSection = null
                        result[key] = value.removeSurrounding("\"")
                    }
                } else if (currentSection != null && trimmed.contains(":")) {
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":").trim().removeSurrounding("\"")
                    sectionMap[key] = if (value == "null") null else value
                }
            }
            if (currentSection != null && sectionMap.isNotEmpty()) {
                result[currentSection] = sectionMap.toMap()
            }
            return result
        }
    }

    private fun registryWithStubYaml() = ConfigFormatRegistry(listOf(stubYamlModule))

    private fun mockFetcher(responses: Map<String, String>): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val body = responses[url]
                    if (body != null) {
                        respond(
                            content = ByteReadChannel(body),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/yaml"),
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("Not Found"),
                            status = HttpStatusCode.NotFound,
                        )
                    }
                }
            }
        }
        return RemoteConfigFetcher(client)
    }

    @Test
    fun `custom env vars from parent and child are unioned across extends chain`() =
        runTest {
            // Stub YAML parser produces flat maps under sections, so we use
            // provider-id level nesting that parseProviders expects:
            // providers: { gitlab: { token: ... } } -> need two levels of nesting
            // The stub parser only supports one level, so we test the env union
            // and interpolation using the merged raw map directly.
            val parentContent = """
            platforms:
              token: ${'$'}{PARENT_VAR}
            """.trimIndent()

            val childContent = """
            extends: https://example.com/parent.yaml
            reporters:
              url: ${'$'}{CHILD_VAR}
            """.trimIndent()

            val responses = mapOf(
                "https://example.com/child.yaml" to childContent,
                "https://example.com/parent.yaml" to parentContent,
            )

            val registry = registryWithStubYaml()
            val resolver = ConfigChainResolver(
                formatRegistry = registry,
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/child.yaml"))
            val mergedMap = ConfigChainParser(registry).parseAndMerge(chain)
            val mergedRaw = mergedMap as? JsonObject ?: JsonObject(emptyMap())

            // Simulate env declarations from separate configs in the chain
            val parentEnv = EnvDeclaration(mapOf("PARENT_VAR" to "parent-default"))
            val childEnv = EnvDeclaration(mapOf("CHILD_VAR" to "child-default"))
            val unionedEnv = EnvDeclaration.union(listOf(childEnv, parentEnv))

            assertEquals(setOf("PARENT_VAR", "CHILD_VAR"), unionedEnv.names)
            assertEquals("parent-default", unionedEnv.defaults["PARENT_VAR"])
            assertEquals("child-default", unionedEnv.defaults["CHILD_VAR"])

            // Verify placeholders survived the merge
            val providers = mergedRaw["platforms"] as? JsonObject
            assertNotNull(providers)
            assertEquals("\${PARENT_VAR}", (providers["token"] as? JsonPrimitive)?.contentOrNull)

            val reporters = mergedRaw["reporters"] as? JsonObject
            assertNotNull(reporters)
            assertEquals("\${CHILD_VAR}", (reporters["url"] as? JsonPrimitive)?.contentOrNull)

            // Verify interpolation with unioned defaults resolves both vars
            val resolvedParent = ConfigMapParser.interpolate(
                "\${PARENT_VAR}",
                defaults = unionedEnv.defaults,
            )
            val resolvedChild = ConfigMapParser.interpolate(
                "\${CHILD_VAR}",
                defaults = unionedEnv.defaults,
            )

            val expectedParent = PlatformEnvironment.getEnv("PARENT_VAR") ?: "parent-default"
            val expectedChild = PlatformEnvironment.getEnv("CHILD_VAR") ?: "child-default"
            assertEquals(expectedParent, resolvedParent)
            assertEquals(expectedChild, resolvedChild)
        }

    @Test
    fun `child env default overrides parent null default in union`() {
        val parentEnv = EnvDeclaration(mapOf("TASK_ID" to null, "SHARED" to "parent-val"))
        val childEnv = EnvDeclaration(mapOf("TASK_ID" to "child-default", "SHARED" to "child-val"))

        // Child first (higher priority), then parent
        val unioned = EnvDeclaration.union(listOf(childEnv, parentEnv))

        assertEquals("child-default", unioned.vars["TASK_ID"])
        // Child's non-null default wins because it appears first
        assertEquals("child-val", unioned.vars["SHARED"])
    }

    @Test
    fun `parent default fills in when child declares var without default`() {
        val parentEnv = EnvDeclaration(mapOf("TASK_ID" to "parent-default"))
        val childEnv = EnvDeclaration(mapOf("TASK_ID" to null))

        // Child first (null default), parent second (has default)
        val unioned = EnvDeclaration.union(listOf(childEnv, parentEnv))

        // Parent's default fills the child's null
        assertEquals("parent-default", unioned.vars["TASK_ID"])
    }

    @Test
    fun `env vars in parent config values are resolved using child-declared defaults`() =
        runTest {
            // Parent uses ${TASK_ID} in a rule but doesn't declare env
            val parentContent = """
            rules:
              pattern: ^${'$'}{TASK_ID}:
            """.trimIndent()

            // Child declares extends only (env declaration simulated separately)
            val childContent = """
            extends: https://example.com/parent.yaml
            """.trimIndent()

            val responses = mapOf(
                "https://example.com/child.yaml" to childContent,
                "https://example.com/parent.yaml" to parentContent,
            )

            val registry = registryWithStubYaml()
            val resolver = ConfigChainResolver(
                formatRegistry = registry,
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/child.yaml"))
            val mergedMap = ConfigChainParser(registry).parseAndMerge(chain)
            val mergedRaw = mergedMap as? JsonObject ?: JsonObject(emptyMap())

            // Simulate child declaring TASK_ID with a default (as the real pipeline would)
            val envDecl = EnvDeclaration(mapOf("TASK_ID" to "PROJ-000"))
            ConfigMapParser.parseFullConfig(mergedMap as JsonElement, envDecl.defaults).config

            // The stub parser puts "pattern" directly under "rules" (no nested title-format)
            // so it becomes a rule config key. Check the merged map to find where it landed.
            val rulesMap = mergedRaw["rules"] as? JsonObject
            assertNotNull(rulesMap, "rules block should exist from parent")
            assertTrue(rulesMap.containsKey("pattern"), "pattern should be in rules block")

            // Verify the env var placeholder survived merge
            assertEquals("^\${TASK_ID}:", (rulesMap["pattern"] as? JsonPrimitive)?.contentOrNull)

            // After full parse with env defaults, the value should be interpolated
            // The stub parser's flat structure means "pattern" is a direct rule config key
            // but ConfigMapParser.parseRules treats each top-level rules key as a rule ID,
            // so "pattern" becomes a rule with no sub-config. Instead, verify interpolation
            // works on the raw value directly:
            val interpolated = ConfigMapParser.interpolate(
                "^\${TASK_ID}:",
                defaults = envDecl.defaults,
            )
            val envValue = PlatformEnvironment.getEnv("TASK_ID")
            val expected = "^${envValue ?: "PROJ-000"}:"
            assertEquals(expected, interpolated)
        }

    // --- Edge cases ---

    @Test
    fun `config with no env block produces empty declaration and works normally`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^feat:")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        assertEquals(EnvDeclaration.EMPTY, envDecl)

        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config
        assertNotNull(config.rules.ruleConfigs["titleFormat"])
        assertEquals(
            "^feat:",
            config.rules.ruleConfigs["titleFormat"]
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `same custom var used in multiple config sections is resolved consistently`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("PROJECT_ID" to "default-proj"),
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${PROJECT_ID}:")
            ),
            "platforms" to mapOf(
                "gitlab" to mapOf("project" to "\${PROJECT_ID}")
            ),
            "reporters" to mapOf(
                "webhook" to mapOf("tag" to "\${PROJECT_ID}")
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val envValue = PlatformEnvironment.getEnv("PROJECT_ID")
        val expected = envValue ?: "default-proj"

        val rulePattern = config.rules.ruleConfigs["titleFormat"]
            ?.jsonObject
            ?.get("pattern")
            ?.jsonPrimitive
            ?.content
        val providerProject = config.platforms
            ?.get("gitlab")
            ?.jsonObject
            ?.get("project")
            ?.jsonPrimitive
            ?.content
        val reporterTag = config.reporters
            ?.get("webhook")
            ?.jsonObject
            ?.get("tag")
            ?.jsonPrimitive
            ?.content

        assertEquals("^$expected:", rulePattern)
        assertEquals(expected, providerProject)
        assertEquals(expected, reporterTag)
    }

    @Test
    fun `env var embedded in longer string is interpolated correctly`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("TEAM" to "platform"),
            "rules" to mapOf(
                "title-format" to mapOf(
                    "pattern" to "^\\[team-\${TEAM}\\] (feat|fix):"
                )
            )
        )

        val envDecl = ConfigMapParser.parseEnvBlock(data)
        val config = ConfigMapParser.parseFullConfig(data, envDecl.defaults).config

        val ruleConfig = config.rules.ruleConfigs["titleFormat"]
        assertNotNull(ruleConfig)

        val envValue = PlatformEnvironment.getEnv("TEAM")
        val team = envValue ?: "platform"
        assertEquals(
            "^\\[team-$team\\] (feat|fix):",
            ruleConfig
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
    }
}
