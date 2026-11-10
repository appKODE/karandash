package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.sdk.typed.TypedModule
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every built-in typed rule module's config class implements [RuleConfig] (generic deserialization, schema). */
class RuleKnobContractTest {
    @Test
    fun `every typed rule config is a RuleConfig`() {
        val typed = ModuleRegistrationCompletenessTest.ALL_RULE_MODULE_FACTORIES
            .map { it() }
            .filterIsInstance<TypedModule<*>>()
        assertTrue(typed.size > 10, "expected the built-in typed rule modules, got ${typed.map { it.id }}")
        val missing = typed
            .map { it.id to Class.forName(it.configSerializer.descriptor.serialName) }
            .filterNot { (_, configClass) -> RuleConfig::class.java.isAssignableFrom(configClass) }
        assertTrue(missing.isEmpty(), "config classes without `: RuleConfig`: $missing")
    }
}
