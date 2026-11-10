package io.karandash.lsp

import kotlin.test.Test

@Suppress("FunctionNaming")
class HoverProviderDebugTest {
    @Test
    fun `debug hover provider on rules block`() {
        val hoverProvider = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))
        val document = """
            rules {
                titleFormat {
                    pattern = "test"
                }
            }
        """.trimIndent()

        println("Document:")
        println(document)
        println("\nLines:")
        document.split("\n").forEachIndexed { index, line ->
            println("$index: '$line'")
        }

        // Try to hover on "rules" at line 0, character 2
        println("\nTrying to hover on line 0, character 2:")
        val hover = hoverProvider.getHover(document, 0, 2)
        println("Result: $hover")

        if (hover != null) {
            println("Content snippet: ${hover.contents.right.value.take(100)}")
        }
    }

    @Test
    fun `debug hover provider on validator name`() {
        val hoverProvider = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))
        val document = """
            rules {
                titleFormat {
                    pattern = "test"
                }
            }
        """.trimIndent()

        println("\nDocument:")
        println(document)
        println("\nLines:")
        document.split("\n").forEachIndexed { index, line ->
            println("$index: '$line'")
        }

        // Try to hover on "titleFormat" at line 1, character 6
        println("\nTrying to hover on line 1, character 6:")
        val hover = hoverProvider.getHover(document, 1, 6)
        println("Result: $hover")

        if (hover != null) {
            println("Content snippet: ${hover.contents.right.value.take(100)}")
        }
    }
}
