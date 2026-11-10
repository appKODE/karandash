package io.karandash.contracts.fw

import io.karandash.contracts.support.parseYamlConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `rule-enrichment:` is always null from YAML/JSON because
 * `ConfigMapParser` looks up the kebab key after keys were normalized to camelCase.
 */
class RuleEnrichmentKeyContractTest {
    @Test
    fun `rule-enrichment block from YAML reaches Config ruleEnrichment`() =
        runTest {
            val config = parseYamlConfig(
                """
                rule-enrichment:
                  ai-review:
                    enrich: [jira.ticket]
                """,
            )
            val bound = assertNotNull(config.ruleEnrichment, "rule-enrichment parsed as null")
            assertEquals(listOf("jira.ticket"), bound["ai-review"]?.enrich)
        }
}
