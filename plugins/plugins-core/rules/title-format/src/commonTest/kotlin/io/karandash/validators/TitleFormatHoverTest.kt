package io.karandash.validators

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The hover text is generated from [TitleFormatConfig], so no key can go missing. */
class TitleFormatHoverTest {
    @Test
    fun `titleFormat hover lists every config key including nested ones`() {
        val hover = assertNotNull(TitleFormatRuleModule().hoverDocumentation)
        val descriptor = TitleFormatConfig.serializer().descriptor
        val missing = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.filterNot { "`$it`" in hover }
        assertTrue(missing.isEmpty(), "hover misses config keys $missing")
        listOf("allowed", "required", "noPeriod").forEach { assertTrue("`$it`" in hover, "hover misses nested key $it") }
    }
}
