package io.karandash.lsp

import kotlin.test.Test
import kotlin.test.assertNotNull

@Suppress("FunctionNaming")
class HoverProviderSimpleTest {
    @Test
    fun `simple test hover on titleFormat`() {
        val hoverProvider = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))
        val document = """
rules {
    titleFormat {
        pattern = "test"
    }
}
        """.trimIndent()

        println("=== Document ===")
        println(document)
        println("\n=== Lines ===")
        document.split("\n").forEachIndexed { i, line ->
            println("$i: '$line'")
        }

        // Line 1 is "    titleFormat {"
        // Character 6 should be in "titleFormat"
        println("\n=== Hovering on line 1, character 6 ===")
        val hover = hoverProvider.getHover(document, 1, 6)

        println("Hover result: $hover")
        if (hover != null) {
            println("Has content: yes")
            println("First 200 chars: ${hover.contents.right.value.take(200)}")
        } else {
            println("Has content: NO - NULL!")
        }

        assertNotNull(hover, "Hover should not be null for titleFormat validator")
    }
}
