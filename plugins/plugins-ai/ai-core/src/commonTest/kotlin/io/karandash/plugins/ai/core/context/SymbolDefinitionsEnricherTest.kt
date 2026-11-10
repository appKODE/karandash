package io.karandash.plugins.ai.core.context

import io.karandash.api.ai.BlockBudget
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolDefinitionsEnricherTest {
    private val head = """
        |package shop
        |
        |class Order(val total: Int)
        |
        |fun discount(order: Order): Int {
        |    if (order.total > LIMIT) return 10
        |    return 0
        |}
        |
        |private const val LIMIT = 100
        |
        |fun checkout(order: Order) {
        |    val d = discount(order)
        |    pay(order.total - d)
        |}
    """.trimMargin()

    private val diff = """
        |@@ -12,3 +12,4 @@
        | fun checkout(order: Order) {
        |+    val d = discount(order)
        |-    pay(order.total)
        |+    pay(order.total - d)
        | }
    """.trimMargin()

    private val change = FileChange("Shop.kt", ChangeType.MODIFIED, diffContent = diff, newContent = head)
    private val enabled =
        buildJsonObject { putJsonObject("contextQuality") { putJsonObject("symbolDefinitions") { put("enabled", true) } } }

    @Test
    fun `finds the definition of a function called on a changed line with its body`() {
        val found = SymbolDefinitions.find(listOf(change))
        assertEquals(listOf("discount"), found.map { it.name })
        val definition = found.single()
        assertEquals(5, definition.line)
        assertEquals("fun discount(order: Order): Int {\n    if (order.total > LIMIT) return 10\n    return 0\n}", definition.snippet)
    }

    @Test
    fun `declaration lines are recognised for kotlin java swift c and cpp`() {
        val cases = mapOf(
            "data class Invoice(val id: String)" to "Invoice",
            "    private suspend fun <T> List<T>.firstPaid(): T? {" to "firstPaid",
            "    private const val LIMIT = 100" to "LIMIT",
            "    public Order(int total) {" to "Order",
            "    public static boolean isValid(String s) {" to "isValid",
            "    func fetchUser(id: Int) async throws -> User {" to "fetchUser",
            "public struct Account: Codable {" to "Account",
            "protocol Store {" to "Store",
            "int Buffer::size() const {" to "size",
            "static bool is_valid(const char* s);" to "is_valid",
            "#define MAX_LEN 64" to "MAX_LEN",
            "typedef unsigned long ulong;" to "ulong",
            "using Callback = std::function<void()>;" to "Callback",
        )
        cases.forEach { (line, name) -> assertEquals(name, SymbolDefinitions.declaredName(line), line) }
    }

    @Test
    fun `calls and control flow are not declarations`() {
        listOf(
            "    return compute(x);",
            "    if (ready) {",
            "    } else if (x > 0) {",
            "    logger.warn(msg)",
            "    new Foo(1);",
            "    foo(bar);"
        ).forEach { assertNull(SymbolDefinitions.declaredName(it), it) }
    }

    @Test
    fun `a definition already shown in the diff is skipped`() {
        val selfDiff = "@@ -5,1 +5,1 @@\n-fun discount(order: Order): Int {\n+fun discount(order: Order): Int {"
        assertTrue(SymbolDefinitions.find(listOf(change.copy(diffContent = selfDiff))).none { it.name == "discount" })
    }

    @Test
    fun `a long body is cut at the snippet limit`() {
        val body = "fun big(): Int {\n" + (1..30).joinToString("\n") { "    step$it()" } + "\n}"
        val bigChange = FileChange("Big.kt", ChangeType.MODIFIED, diffContent = "@@ -40,1 +40,1 @@\n-big()\n+big() + 1", newContent = body)
        val snippet = SymbolDefinitions.find(listOf(bigChange)).single().snippet
        assertEquals(SymbolDefinitions.MAX_SNIPPET_LINES + 1, snippet.lines().size)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `enricher is off by default and emits a block when enabled`() =
        runTest {
            val enricher = SymbolDefinitionsEnricher()
            val mr = createTestMergeRequest()
            assertFalse(SymbolDefinitionsEnricher.isActive(JsonNull))
            assertNull(enricher.provideBlock(mr, listOf(change), JsonNull, BlockBudget.UNLIMITED))

            assertTrue(SymbolDefinitionsEnricher.isActive(enabled))
            val block = assertNotNull(enricher.provideBlock(mr, listOf(change), enabled, BlockBudget.UNLIMITED))
            assertEquals(SymbolDefinitionsEnricher.BLOCK_PRIORITY, block.priority)
            assertTrue(block.content.startsWith("`Shop.kt` L5 (`discount`):"), block.content)
        }

    @Test
    fun `enricher without head content emits nothing`() =
        runTest {
            val noHead = change.copy(newContent = null)
            assertNull(SymbolDefinitionsEnricher().provideBlock(createTestMergeRequest(), listOf(noHead), enabled, BlockBudget.UNLIMITED))
        }
}
