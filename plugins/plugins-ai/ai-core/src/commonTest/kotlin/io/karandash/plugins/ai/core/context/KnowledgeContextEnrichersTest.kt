package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeContextEnrichersTest {
    private val mr = createTestMergeRequest(title = "Add feature")
    private val ctx = testPluginContext()

    // -- KnowledgeContextFormatter --

    @Test
    fun `formatBlock returns null for blank content`() {
        assertNull(KnowledgeContextFormatter.formatBlock("h", "   ", 100, "src", BlockBudget.UNLIMITED))
    }

    @Test
    fun `formatBlock over its budget records the length it was cut from`() {
        val block = KnowledgeContextFormatter.formatBlock("h", "line one\nline two\nline three", 1, "src", BlockBudget(12))
        assertEquals(28, assertNotNull(block).cutFromChars)
    }

    @Test
    fun `formatBlock within its budget records no cut`() {
        assertNull(assertNotNull(KnowledgeContextFormatter.formatBlock("h", "short", 1, "src", BlockBudget(100))).cutFromChars)
    }

    @Test
    fun `formatBlock leaves the untrusted-content boundary to the assembler`() {
        val block = KnowledgeContextFormatter.formatBlock("#### H", "hello", 100, "my-src", BlockBudget.UNLIMITED)
        assertNotNull(block)
        assertEquals("hello", block.content)
    }

    @Test
    fun `truncateToBudget respects line boundary`() {
        val text = "line1\nline2\nline3"
        val result = KnowledgeContextFormatter.truncateToBudget(text, 11)
        assertTrue(result.startsWith("line1\n"))
        assertTrue(result.contains("[content truncated]"))
        assertTrue(!result.contains("line2"))
    }

    @Test
    fun `truncateToBudget returns text unchanged when within budget`() {
        val text = "short"
        assertEquals(text, KnowledgeContextFormatter.truncateToBudget(text, 100))
    }

    @Test
    fun `truncateToBudget unlimited budget returns full text`() {
        val text = "a".repeat(1000)
        assertEquals(text, KnowledgeContextFormatter.truncateToBudget(text, Int.MAX_VALUE))
    }

    // -- TicketContentEnricher --

    @Test
    fun `TicketContentEnricher returns null for JsonNull`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `TicketContentEnricher returns null when no handled keys in config`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject { put("unrelated", "data") }
            assertNull(e.provideBlock(mr, emptyList(), config))
        }

    @Test
    fun `TicketContentEnricher formats jira tickets`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_TICKETS,
                    buildJsonObject {
                        put(
                            "PROJ-1",
                            buildJsonObject {
                                put("summary", "Fix login bug")
                                put("description", "SSO users cannot log in")
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertEquals(TicketContentEnricher.BLOCK_HEADING, block.heading)
            assertTrue(block.content.contains("Fix login bug"))
        }

    @Test
    fun `TicketContentEnricher redacts secrets and PII in ticket text before it reaches the prompt`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_TICKETS,
                    buildJsonObject {
                        put(
                            "PROJ-2",
                            buildJsonObject {
                                put("summary", "Rotate creds")
                                put("description", "Ask alice@example.com and use Authorization: Bearer abcdef0123456789abcdef")
                            }
                        )
                    }
                )
            }
            val content = checkNotNull(e.provideBlock(mr, emptyList(), config)).content
            assertTrue("Rotate creds" in content)
            assertTrue("alice@example.com" !in content && "[PII:email]" in content, content)
            assertTrue("abcdef0123456789abcdef" !in content, content)
        }

    @Test
    fun `TicketContentEnricher keeps a long ticket description whole`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val description = (1..80).joinToString("\n") { "step $it of the acceptance flow" }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_TICKETS,
                    buildJsonObject {
                        put(
                            "PROJ-2",
                            buildJsonObject {
                                put("summary", "Long ticket")
                                put("description", description)
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertTrue(block.content.contains("step 80 of the acceptance flow"), "the ticket body is T0 and never cut")
        }

    @Test
    fun `TicketContentEnricher formats acceptance criteria`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA,
                    buildJsonObject {
                        put(
                            "PROJ-1",
                            buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive("User can log in with SSO"))
                                add(kotlinx.serialization.json.JsonPrimitive("Error message shown on failure"))
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertTrue(block.content.contains("User can log in with SSO"))
        }

    @Test
    fun `TicketContentEnricher redacts PII from ticket content`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_TICKETS,
                    buildJsonObject {
                        put(
                            "PROJ-1",
                            buildJsonObject {
                                put("summary", "Bug report from alice@example.com")
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertTrue(!block.content.contains("alice@example.com"), "Email should be redacted")
            assertTrue(block.content.contains("[PII:email]"))
        }

    @Test
    fun `TicketContentEnricher enrich returns empty map`() =
        runTest {
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val result = e.enrich(
                mr,
                io.karandash.api.ai
                    .AiContextEnricherContext(ruleId = null, existingContext = emptyMap())
            )
            assertTrue(result.isEmpty())
        }

    @Test
    fun `TicketContentEnricher handledEnrichmentIds includes all ticket sources`() {
        val ids = TicketContentEnricher().handledEnrichmentIds()
        assertTrue(EnrichmentKeys.JIRA_TICKETS in ids)
        assertTrue(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA in ids)
        assertTrue(EnrichmentKeys.GITHUB_ISSUES in ids)
        assertTrue(EnrichmentKeys.GITLAB_ISSUES in ids)
    }

    // -- DesignDocEnricher --

    @Test
    fun `DesignDocEnricher returns null for JsonNull`() =
        runTest {
            val e = DesignDocEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `DesignDocEnricher formats confluence pages`() =
        runTest {
            val e = DesignDocEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.CONFLUENCE_PAGES,
                    buildJsonObject {
                        put(
                            "12345",
                            buildJsonObject {
                                put("title", "Auth Design")
                                put("markdown", "## Overview\nAuth uses OAuth2.")
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertEquals(DesignDocEnricher.BLOCK_HEADING, block.heading)
            assertTrue(block.content.contains("Auth Design"))
            assertTrue(block.content.contains("OAuth2"))
        }

    @Test
    fun `DesignDocEnricher redacts secrets in design-doc text before design-doc-drift sends it`() =
        runTest {
            val e = DesignDocEnricher().also { it.initialize(ctx) }
            val token = "ghp_" + "A".repeat(36)
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.CONFLUENCE_PAGES,
                    buildJsonObject {
                        put(
                            "1",
                            buildJsonObject {
                                put("title", "Deploy $token")
                                put("markdown", "Use token $token for deploys")
                            }
                        )
                    }
                )
            }
            val content = checkNotNull(e.provideBlock(mr, emptyList(), config)).content
            assertTrue(token !in content, content)
            assertTrue("for deploys" in content, content)
        }

    @Test
    fun `DesignDocEnricher returns null when no confluence pages in config`() =
        runTest {
            val e = DesignDocEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject { put("other", "data") }
            assertNull(e.provideBlock(mr, emptyList(), config))
        }

    // -- CiContextEnricher --

    @Test
    fun `CiContextEnricher returns null for JsonNull`() =
        runTest {
            val e = CiContextEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `CiContextEnricher formats failed jobs`() =
        runTest {
            val e = CiContextEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.CI_FAILED_JOBS,
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "unit-test")
                                put("stage", "test")
                                put("errorSnippet", "AssertionError at line 42")
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertEquals(CiContextEnricher.BLOCK_HEADING, block.heading)
            assertTrue(block.content.contains("unit-test"))
            assertTrue(block.content.contains("AssertionError"))
        }

    @Test
    fun `CiContextEnricher formats coverage gaps`() =
        runTest {
            val e = CiContextEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.CI_COVERAGE_GAPS,
                    buildJsonObject {
                        put(
                            "src/Auth.kt",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("startLine", 42)
                                        put("endLine", 55)
                                    }
                                )
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertTrue(block.content.contains("src/Auth.kt"))
            assertTrue(block.content.contains("L42-L55"))
        }

    @Test
    fun `CiContextEnricher returns null when no ci keys in config`() =
        runTest {
            val e = CiContextEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), buildJsonObject { put("x", "y") }))
        }

    // -- SymbolContextEnricher --

    @Test
    fun `SymbolContextEnricher returns null for JsonNull`() =
        runTest {
            val e = SymbolContextEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `SymbolContextEnricher formats symbol signatures`() =
        runTest {
            val e = SymbolContextEnricher().also { it.initialize(ctx) }
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.SYMBOLS_CHANGED,
                    buildJsonObject {
                        put(
                            "src/Auth.kt",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("kind", "fun")
                                        put("name", "authenticate")
                                        put("signature", "fun authenticate(token: String): User?")
                                    }
                                )
                            }
                        )
                    }
                )
            }
            val block = e.provideBlock(mr, emptyList(), config)
            assertNotNull(block)
            assertEquals(SymbolContextEnricher.BLOCK_HEADING, block.heading)
            assertTrue(block.content.contains("src/Auth.kt"))
            assertTrue(block.content.contains("authenticate"))
        }

    @Test
    fun `SymbolContextEnricher returns null when no symbols in config`() =
        runTest {
            val e = SymbolContextEnricher().also { it.initialize(ctx) }
            assertNull(e.provideBlock(mr, emptyList(), buildJsonObject { put("x", "y") }))
        }

    @Test
    fun `all enrichers respect budget truncation`() =
        runTest {
            val budget = BlockBudget(maxChars = 20)
            val config = buildJsonObject {
                put(
                    EnrichmentKeys.JIRA_TICKETS,
                    buildJsonObject {
                        put(
                            "PROJ-1",
                            buildJsonObject {
                                put("summary", "A very long summary that exceeds the budget for sure and then some more")
                            }
                        )
                    }
                )
            }
            val e = TicketContentEnricher().also { it.initialize(ctx) }
            val block = e.provideBlock(mr, emptyList(), config, budget)
            assertNotNull(block)
            assertTrue(block.content.contains("[content truncated]"))
        }
}

private fun testPluginContext(): PluginContext =
    object : PluginContext {
        override val pluginConfig: kotlinx.serialization.json.JsonElement =
            JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"
        override val logger: Logger = NoOpLogger

        override fun env(name: String): String? = null
    }
