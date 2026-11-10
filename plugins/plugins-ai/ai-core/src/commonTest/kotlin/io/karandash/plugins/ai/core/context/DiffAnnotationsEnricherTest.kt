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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffAnnotationsEnricherTest {
    private val kotlinDiff = """
        |@@ -10,8 +10,7 @@ fun save(user: User) {
        |-    if (!featureFlags.isSaveEnabled()) return
        |-    require(user.id > 0)
        |     val repo = repository()
        |+    repo.save(user)
        |     if (user.isNew) {
        |-        repo.save(user)
        |         audit(user)
        |     }
    """.trimMargin()

    @Test
    fun `kotlin removed feature check and require are flagged as removed guards`() {
        val notes = DiffAnnotations.annotate("Save.kt", kotlinDiff)
        assertTrue(notes.any { it.startsWith("Removed guard at Save.kt near L10: `if (!featureFlags.isSaveEnabled()) return`") }, "$notes")
        assertTrue(notes.any { it.startsWith("Removed guard at Save.kt near L10: `require(user.id > 0)`") }, "$notes")
    }

    @Test
    fun `kotlin call moved out of an if is flagged as moved across a condition`() {
        val notes = DiffAnnotations.annotate("Save.kt", kotlinDiff)
        assertTrue(
            notes.any { it.startsWith("Moved across a condition or block in Save.kt: `repo.save(user)` from near L13 to L11") },
            "$notes",
        )
        assertEquals(3, notes.size, "$notes")
    }

    @Test
    fun `swift removed guard and reordered call are flagged`() {
        val diff = """
            |@@ -1,6 +1,6 @@
            | func load() {
            |-    guard isAuthorized else { return }
            |+    cache.clear()
            |     fetch()
            |-    cache.clear()
            | }
        """.trimMargin()
        val notes = DiffAnnotations.annotate("Loader.swift", diff)
        assertTrue(notes.any { it.startsWith("Removed guard at Loader.swift near L2: `guard isAuthorized else { return }`") }, "$notes")
        assertTrue(notes.any { it.startsWith("Moved in Loader.swift: `cache.clear()` from near L4 to L2") }, "$notes")
    }

    @Test
    fun `cpp removed bounds check early return and DCHECK are flagged`() {
        val diff = """
            |@@ -20,7 +20,4 @@ void Buffer::write(const char* data, size_t len) {
            |-    DCHECK(data != nullptr);
            |-    if (len > capacity_) {
            |-        return;
            |-    }
            |     memcpy(buf_, data, len);
        """.trimMargin()
        val notes = DiffAnnotations.annotate("buffer.cpp", diff)
        assertEquals(
            listOf("`DCHECK(data != nullptr)`", "`if (len > capacity_) {`", "`return`"),
            notes.map { it.substringAfter(": ").substringBefore(" — ") },
        )
    }

    @Test
    fun `a reindented line in place and an unrelated removal produce no notes`() {
        val diff = """
            |@@ -1,4 +1,4 @@
            |-  if (a) {
            |-  val label = format(name)
            |+    if (a) {
            |+    val label = format(name)
            |   }
        """.trimMargin()
        assertEquals(emptyList(), DiffAnnotations.annotate("A.kt", diff))
        assertEquals(emptyList(), DiffAnnotations.annotate("A.kt", "@@ -1,2 +1,1 @@\n-val unused = 1\n keep()"))
    }

    @Test
    fun `notes are capped per file`() {
        val diff = "@@ -1,10 +1,1 @@\n" + (1..10).joinToString("\n") { "-check(x$it)" } + "\n keep()"
        assertEquals(DiffAnnotations.MAX_NOTES_PER_FILE, DiffAnnotations.annotate("A.kt", diff).size)
    }

    @Test
    fun `enricher emits a block by default and none when disabled`() =
        runTest {
            val enricher = DiffAnnotationsEnricher()
            val mr = createTestMergeRequest()
            val changes = listOf(FileChange("Save.kt", ChangeType.MODIFIED, diffContent = kotlinDiff))
            val block = assertNotNull(enricher.provideBlock(mr, changes, JsonNull, BlockBudget.UNLIMITED))
            assertEquals(DiffAnnotationsEnricher.BLOCK_PRIORITY, block.priority)
            assertTrue(block.content.contains("Removed guard at Save.kt"), block.content)

            val off = buildJsonObject { putJsonObject("contextQuality") { putJsonObject("diffAnnotations") { put("enabled", false) } } }
            assertNull(enricher.provideBlock(mr, changes, off, BlockBudget.UNLIMITED))
            val masterOff = buildJsonObject { putJsonObject("contextQuality") { put("enabled", false) } }
            assertNull(enricher.provideBlock(mr, changes, masterOff, BlockBudget.UNLIMITED))
        }

    @Test
    fun `enricher skips sensitive files`() =
        runTest {
            val changes = listOf(FileChange(".env", ChangeType.MODIFIED, diffContent = "@@ -1,1 +1,0 @@\n-require(token)"))
            assertNull(DiffAnnotationsEnricher().provideBlock(createTestMergeRequest(), changes, JsonNull, BlockBudget.UNLIMITED))
        }

    @Test
    fun `removed guards of every file come before any move`() {
        val moveOnly = """
            |@@ -1,4 +1,4 @@
            |+    cache.clear()
            |     fetch()
            |-    cache.clear()
        """.trimMargin()
        val changes = listOf(
            FileChange("Move.kt", ChangeType.MODIFIED, diffContent = moveOnly),
            FileChange("Guard.kt", ChangeType.MODIFIED, diffContent = "@@ -1,2 +1,1 @@\n-    require(user.id > 0)\n     save(user)"),
        )
        val block = checkNotNull(DiffAnnotationsEnricher.blockFor(changes, BlockBudget.UNLIMITED))
        val notes = block.content.lines().filter { it.startsWith("- ") }
        assertEquals(
            listOf("- Removed guard at Guard.kt", "- Moved in Move.kt"),
            notes.map { it.substringBefore(" near").substringBefore(":") }
        )
    }
}
