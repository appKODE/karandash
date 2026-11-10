package io.karandash.plugins.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlackHoverDocumentationTest {
    @Test
    fun `the hover documentation lists the format config keys and no delivery keys`() {
        val doc = SlackFormatModule().hoverDocumentation
        val documented = Regex("""^- `(\w+)""", RegexOption.MULTILINE).findAll(doc).map { it.groupValues[1] }.toSet()
        val descriptor = SlackFormatConfig.serializer().descriptor
        assertEquals((0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet(), documented)
        assertFalse(doc.contains("webhook:"), doc)
    }
}
