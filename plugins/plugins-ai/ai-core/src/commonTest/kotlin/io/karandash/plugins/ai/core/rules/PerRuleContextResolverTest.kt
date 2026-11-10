package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PerRuleContextResolverTest {
    @Test
    fun `null rule id returns global unchanged`() {
        val global = AiContextQualityConfig()
        val resolved = PerRuleContextResolver.resolveForRule(null, global)
        assertSame(global, resolved)
    }

    @Test
    fun `unknown rule id returns global unchanged`() {
        val global = AiContextQualityConfig()
        val resolved = PerRuleContextResolver.resolveForRule("not-configured", global)
        assertSame(global, resolved)
    }

    @Test
    fun `override replaces only the named sub-config`() {
        val overridden = RelatedFilesConfig(enabled = false)
        val global = AiContextQualityConfig(
            ruleContext = mapOf(
                "review-comments" to PerRuleContextConfig(relatedFiles = overridden),
            ),
        )
        val resolved = PerRuleContextResolver.resolveForRule("review-comments", global)
        assertEquals(overridden, resolved.relatedFiles)
        assertEquals(global.fullFileContext, resolved.fullFileContext)
        assertEquals(global.conventions, resolved.conventions)
        assertEquals(global.findingValidator, resolved.findingValidator)
        assertEquals(global.truncation, resolved.truncation)
    }

    @Test
    fun `override with all fields set replaces each sub-config`() {
        val override = PerRuleContextConfig(
            truncation = TruncationConfig(strategy = TruncationStrategy.PRIORITY),
            relatedFiles = RelatedFilesConfig(enabled = false),
            fullFileContext = FullFileContextConfig(enabled = true, maxFiles = 2),
            conventions = ConventionsConfig(autoDetect = false),
            findingValidator = FindingValidatorConfig(enabled = false, strict = false),
        )
        val global = AiContextQualityConfig(
            ruleContext = mapOf("rule" to override),
        )
        val resolved = PerRuleContextResolver.resolveForRule("rule", global)
        assertEquals(override.truncation, resolved.truncation)
        assertEquals(override.relatedFiles, resolved.relatedFiles)
        assertEquals(override.fullFileContext, resolved.fullFileContext)
        assertEquals(override.conventions, resolved.conventions)
        assertEquals(override.findingValidator, resolved.findingValidator)
    }

    @Test
    fun `rule id is normalised - matches regardless of case`() {
        val override = RelatedFilesConfig(enabled = false)
        val global = AiContextQualityConfig(
            ruleContext = mapOf(
                "review-comments" to PerRuleContextConfig(relatedFiles = override),
            ),
        )
        val a = PerRuleContextResolver.resolveForRule("Review_Comments", global)
        val b = PerRuleContextResolver.resolveForRule("review-comments", global)
        assertEquals(a, b)
        assertEquals(override, a.relatedFiles)
    }

    @Test
    fun `rule id with unsupported characters normalises to hyphens`() {
        val override = RelatedFilesConfig(enabled = false)
        val global = AiContextQualityConfig(
            ruleContext = mapOf("some-rule-v2" to PerRuleContextConfig(relatedFiles = override)),
        )
        val resolved = PerRuleContextResolver.resolveForRule("Some/Rule v2", global)
        assertEquals(override, resolved.relatedFiles)
    }
}
