package io.karandash.plugins.outputs

import kotlin.test.Test
import kotlin.test.assertEquals

class SlackOutputHoverDocumentationTest {
    @Test
    fun `the hover documentation lists every user-set output config key`() {
        val doc = SlackOutputModule().hoverDocumentation
        val documented = Regex("""^- `(\w+)""", RegexOption.MULTILINE).findAll(doc).map { it.groupValues[1] }.toSet()
        val descriptor = SlackOutputConfig.serializer().descriptor
        val keys = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet() - "notificationTarget"
        assertEquals(keys, documented)
    }
}
