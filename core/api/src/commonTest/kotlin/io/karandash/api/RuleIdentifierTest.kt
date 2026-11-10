package io.karandash.api

import io.karandash.api.finding.RuleIdentifier
import io.karandash.api.finding.ruleIdentifier
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleIdentifierTest {
    @Test
    fun fromBuiltInKey() {
        val id = RuleIdentifier.from("titleFormat")
        assertEquals("titleFormat", id.key)
    }

    @Test
    fun fromKebabKey() {
        val id = RuleIdentifier.from("title-format")
        assertEquals("title-format", id.key)
    }

    @Test
    fun fromExternalKey() {
        val id = RuleIdentifier.from("custom-plugin-rule")
        assertEquals("custom-plugin-rule", id.key)
    }

    @Test
    fun equality() {
        assertEquals(
            RuleIdentifier.from("titleFormat"),
            RuleIdentifier.from("titleFormat"),
        )
    }

    @Test
    fun findingExtensionProperty() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "test",
            rule = "titleFormat",
        )
        assertEquals("titleFormat", finding.ruleIdentifier.key)
    }

    @Test
    fun findingExtensionPropertyExternal() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "test",
            rule = "my-custom-rule",
        )
        assertEquals("my-custom-rule", finding.ruleIdentifier.key)
    }
}
