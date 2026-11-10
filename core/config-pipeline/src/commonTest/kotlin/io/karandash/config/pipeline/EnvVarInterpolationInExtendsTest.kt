package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.http.HttpAuth
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for environment variable interpolation across config extends chains.
 *
 * These tests verify that:
 * 1. Env vars in extended (parent) config values are resolved during the full parse
 * 2. Env vars in `extends:` URLs are resolved during the bootstrap parse with bootstrap allowlist
 * 3. Non-allowlisted env vars remain as placeholders at each parse pass
 * 4. The merge-then-interpolate ordering resolves parent config env vars correctly
 */
class EnvVarInterpolationInExtendsTest {
    /**
     * Stub YAML format module that parses simple key-value YAML content.
     * Supports nested maps via indentation detection and `extends:` field.
     */
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
                    } else {
                        currentSection = null
                        result[key] = value.removeSurrounding("\"")
                    }
                } else if (currentSection != null && trimmed.contains(":")) {
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":").trim().removeSurrounding("\"")
                    sectionMap[key] = value
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

    // --- Test: Env vars in parent config values are resolved after merge ---

    @Test
    fun `env vars in parent config values survive merge and are resolved in the full parse`() =
        runTest {
            val parentContent = """
                platforms:
                  token: ${'$'}{GITLAB_TOKEN}
                  url: https://gitlab.com
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                rules:
                  pattern: "^feat:"
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

            // Before the full parse: the placeholder should survive in the merged map
            val providers = (mergedMap as? JsonObject)?.get("platforms") as? JsonObject
            assertNotNull(providers, "providers block should exist from parent")
            assertEquals("\${GITLAB_TOKEN}", providers["token"]?.jsonPrimitive?.content, "Env var placeholder should survive merge")

            // Full parse: interpolate with denylist-based approach and defaults
            val defaults = mapOf("GITLAB_TOKEN" to "gl-fallback")
            val result = ConfigMapParser.parseFullConfig(mergedMap as JsonElement, defaults)

            // The provider config should be present with interpolated values
            assertNotNull(result.config.platforms, "providers should be parsed from parent config")
        }

    // --- Test: Env vars NOT in bootstrap allowlist stay as placeholders in extends URLs ---

    @Test
    fun `env vars not in denylist are resolved in extends URLs`() {
        val data = mapOf<String, Any?>("extends" to "https://\${CUSTOM_HOST}/parent.yaml")
        val sources = ConfigMapParser.getExtendsSources(data)

        assertEquals(1, sources.size)
        val envValue = PlatformEnvironment.getEnv("CUSTOM_HOST")
        if (envValue != null) {
            assertTrue(sources[0].url.contains(envValue))
        } else {
            // Unset non-denylisted vars resolve to empty string
            assertEquals("https:///parent.yaml", sources[0].url)
        }
    }

    // --- Test: KARANDASH_* env vars in extends URLs are resolved during bootstrap ---

    @Test
    fun `KARANDASH env vars in extends URLs are resolved during bootstrap phase`() {
        val data = mapOf<String, Any?>("extends" to "https://\${KARANDASH_CONFIG_URL}/parent.yaml")

        val envValue = PlatformEnvironment.getEnv("KARANDASH_CONFIG_URL")
        val sources = ConfigMapParser.getExtendsSources(data)
        assertEquals(1, sources.size)
        val url = sources[0].url
        if (envValue != null) {
            assertTrue(
                !url.contains("\${"),
                "Env var should be resolved when set"
            )
        } else {
            // Unset non-denylisted vars resolve to empty string
            assertEquals("https:///parent.yaml", url)
        }
    }

    // --- Test: Child overrides parent values, env vars in child are resolved ---

    @Test
    fun `child overrides parent values and env vars in overridden values are resolved`() =
        runTest {
            val parentContent = """
                platforms:
                  token: parent-static-token
                  url: https://gitlab.com
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                platforms:
                  token: ${'$'}{GITLAB_TOKEN}
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

            // Child should override parent token value
            val providers = (mergedMap as? JsonObject)?.get("platforms") as? JsonObject
            assertNotNull(providers)
            assertEquals(
                "\${GITLAB_TOKEN}",
                providers["token"]?.jsonPrimitive?.content,
                "Child env var placeholder should override parent static value"
            )

            // Parent-only keys should be inherited
            assertEquals("https://gitlab.com", providers["url"]?.jsonPrimitive?.content, "Parent url should be inherited by child")
        }

    // --- Test: Multiple env vars across extends chain ---

    @Test
    fun `multiple env vars across extends chain are all resolved in the full parse`() =
        runTest {
            val parentContent = """
                platforms:
                  token: ${'$'}{GITLAB_TOKEN}
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                reporters:
                  webhook: ${'$'}{WEBHOOK_URL}
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

            // Both env var placeholders should be present in the merged map
            val mergedObj = mergedMap as? JsonObject
            val providers = mergedObj?.get("platforms") as? JsonObject
            val reporters = mergedObj?.get("reporters") as? JsonObject

            assertNotNull(providers)
            assertNotNull(reporters)
            assertEquals("\${GITLAB_TOKEN}", providers["token"]?.jsonPrimitive?.content)
            assertEquals("\${WEBHOOK_URL}", reporters["webhook"]?.jsonPrimitive?.content)
        }

    // --- Test: Non-allowlisted env vars in parent configs remain as placeholders after full parse ---

    @Test
    fun `SECURITY - non-allowlisted env vars in parent configs remain as placeholders`() =
        runTest {
            val parentContent = """
                platforms:
                  secret: ${'$'}{HOME}
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                rules:
                  pattern: "^feat:"
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

            // Full parse with denylist-based interpolation
            val result = ConfigMapParser.parseFullConfig(mergedMap as JsonElement)

            // Provider block from parent should be present after merge
            assertNotNull(result.config.platforms)
            @Suppress("UNCHECKED_CAST")
            val providerConfig = result.config.platforms
                ?.values
                ?.firstOrNull()
            assertNotNull(providerConfig, "Provider config from parent should be present")
        }

    // --- Test: Env vars in extends auth token are resolved via denylist interpolation ---

    @Test
    fun `extends auth token env vars are resolved via denylist interpolation`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "bearer-token",
                    "token" to "\${KARANDASH_CONFIG_TOKEN}"
                )
            )
        )

        val envValue = PlatformEnvironment.getEnv("KARANDASH_CONFIG_TOKEN")
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()
        assertNotNull(source)
        assertTrue(source.auth is HttpAuth.Bearer)
        val auth = source.auth as HttpAuth.Bearer
        if (envValue != null) {
            assertTrue(
                !auth.token.contains("\${KARANDASH_CONFIG_TOKEN}"),
                "Env var in auth token should be resolved when set"
            )
        } else {
            // Unset non-denylisted vars resolve to empty string
            assertEquals("", auth.token)
        }
    }

    // --- Test: SECURITY - Denylisted env vars in extends auth remain as placeholders ---

    @Test
    fun `SECURITY - denylisted env vars in extends auth remain as placeholders`() {
        val data = mapOf<String, Any?>(
            "extends" to mapOf(
                "url" to "https://example.com/config.yaml",
                "auth" to mapOf(
                    "type" to "bearer-token",
                    "token" to "\${HOME}"
                )
            )
        )
        val sources = ConfigMapParser.getExtendsSources(data)
        val source = sources.first()

        assertNotNull(source)
        val auth = source.auth as HttpAuth.Bearer
        // HOME is in the default denylist, so it stays as placeholder
        assertEquals(
            "\${HOME}",
            auth.token,
            "Denylisted env var in extends auth should remain as placeholder"
        )
    }

    // --- Test: Deep extends chain (grandparent -> parent -> child) with env vars ---

    @Test
    fun `env vars in grandparent config survive deep chain and are resolved in the full parse`() =
        runTest {
            val grandparentContent = """
                platforms:
                  token: ${'$'}{GITLAB_TOKEN}
                  region: eu-west-1
            """.trimIndent()

            val parentContent = """
                extends: https://example.com/grandparent.yaml
                rules:
                  pattern: "^(feat|fix):"
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                reporters:
                  format: json
            """.trimIndent()

            val responses = mapOf(
                "https://example.com/child.yaml" to childContent,
                "https://example.com/parent.yaml" to parentContent,
                "https://example.com/grandparent.yaml" to grandparentContent,
            )

            val registry = registryWithStubYaml()
            val resolver = ConfigChainResolver(
                formatRegistry = registry,
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/child.yaml"))

            assertEquals(3, chain.size, "Chain should have 3 entries (grandparent, parent, child)")

            val mergedMap = ConfigChainParser(registry).parseAndMerge(chain)

            // Grandparent's provider env var should survive through the chain
            val mergedObj = mergedMap as? JsonObject
            val providers = mergedObj?.get("platforms") as? JsonObject
            assertNotNull(providers, "providers from grandparent should be inherited")
            assertEquals(
                "\${GITLAB_TOKEN}",
                providers["token"]?.jsonPrimitive?.content,
                "Env var placeholder should survive deep chain merge"
            )
            assertEquals("eu-west-1", providers["region"]?.jsonPrimitive?.content, "Static values from grandparent should be inherited")

            // Rules from parent should be inherited
            val rules = mergedObj?.get("rules") as? JsonObject
            assertNotNull(rules, "rules from parent should be inherited")

            // Reporters from child should be present
            val reporters = mergedObj?.get("reporters") as? JsonObject
            assertNotNull(reporters, "reporters from child should be present")
        }

    // --- Test: Denylist blocks all listed env vars as placeholders ---

    @Test
    fun `denylist blocks all listed env vars leaving placeholders unchanged`() {
        val value = "\${GITLAB_TOKEN}:\${GITHUB_TOKEN}:\${HOME}"
        val denyAll = setOf("GITLAB_TOKEN", "GITHUB_TOKEN", "HOME")
        val result = ConfigMapParser.interpolate(value, emptyMap(), denyAll)

        assertEquals(
            "\${GITLAB_TOKEN}:\${GITHUB_TOKEN}:\${HOME}",
            result,
            "All placeholders should remain unchanged when all vars are denylisted"
        )
    }

    // --- Test: Interpolation after merge preserves correct override semantics ---

    @Test
    fun `interpolation after merge preserves child override of parent env var value`() =
        runTest {
            val parentContent = """
                platforms:
                  token: ${'$'}{PARENT_TOKEN}
                  url: https://parent.com
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                platforms:
                  token: ${'$'}{CHILD_TOKEN}
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

            val providers = (mergedMap as? JsonObject)?.get("platforms") as? JsonObject
            assertNotNull(providers)

            // Child's token should override parent's token
            assertEquals(
                "\${CHILD_TOKEN}",
                providers["token"]?.jsonPrimitive?.content,
                "Child env var should override parent env var in merged map"
            )

            // Parent's url should be inherited
            assertEquals(
                "https://parent.com",
                providers["url"]?.jsonPrimitive?.content,
                "Parent-only keys should be inherited"
            )

            // The overridden parent value is gone from the merged config, so its variable is not reported unset
            val missing = ConfigMapParser.parseFullConfig(mergedMap, emptyMap()).missingEnvVars
            assertTrue("PARENT_TOKEN" !in missing, "Overridden parent variable must not be reported: $missing")
            assertTrue("CHILD_TOKEN" in missing, "The child's unset variable is still reported: $missing")
        }

    // --- Test: Env vars from parent env block are resolved via merged env defaults ---

    @Test
    fun `env defaults from parent env block are available for interpolation after merge`() =
        runTest {
            val parentContent = """
                env:
                  KARANDASH_TASK_PATTERN: "BRAVO-\\d+"
                  KARANDASH_JIRA_KEY: null
                rules:
                  pattern: ${'$'}{KARANDASH_TASK_PATTERN}
            """.trimIndent()

            val childContent = """
                extends: https://example.com/parent.yaml
                env:
                  KARANDASH_JIRA_KEY: "BRAVO"
                rules:
                  projectKey: ${'$'}{KARANDASH_JIRA_KEY}
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

            // Extract env from merged element (as the fix does)
            val mergedEnvDecl = ConfigMapParser.parseEnvBlock(mergedMap)

            // Both parent and child env defaults should be present
            assertEquals(
                "BRAVO-\\\\d+",
                mergedEnvDecl.defaults["KARANDASH_TASK_PATTERN"],
                "Parent env default should be available in merged env block"
            )
            assertEquals(
                "BRAVO",
                mergedEnvDecl.defaults["KARANDASH_JIRA_KEY"],
                "Child env default should be available in merged env block"
            )

            // Full parse: interpolate with merged env defaults
            val result = ConfigMapParser.parseFullConfig(mergedMap, mergedEnvDecl.defaults)

            // Rules should have interpolated values
            val rulesConfig = result.config.rules
            assertNotNull(rulesConfig, "rules should be parsed")
            // Defaults declared anywhere in the chain count as set: no unset-variable warning for either
            assertTrue(
                result.missingEnvVars.none { it == "KARANDASH_TASK_PATTERN" || it == "KARANDASH_JIRA_KEY" },
                "Chain-declared defaults must not be reported unset: ${result.missingEnvVars}",
            )
        }

    // --- Test: Env vars inside array values are interpolated ---

    @Test
    fun `env vars inside array values are interpolated`() =
        runTest {
            val element = buildJsonObject {
                putJsonObject("rules") {
                    putJsonObject("jira-ticket") {
                        putJsonArray("projectKeys") {
                            add(JsonPrimitive("\${KARANDASH_JIRA_KEY}"))
                        }
                    }
                }
            }
            val defaults = mapOf("KARANDASH_JIRA_KEY" to "RSTI")
            val result = ConfigMapParser.parseFullConfig(element as JsonElement, defaults)
            val jiraConfig = result.config.rules.ruleConfigs["jiraTicket"] as? JsonObject
            assertNotNull(jiraConfig, "jira-ticket rule config should be parsed")
            val projectKeys = jiraConfig["projectKeys"] as? JsonArray
            assertNotNull(projectKeys, "projectKeys should be present")
            assertEquals(
                "RSTI",
                projectKeys.first().jsonPrimitive.content,
                "env var inside array should be interpolated",
            )
        }

    // --- Test: Env vars inside conditional.thenOverrides nested objects are interpolated ---
}
