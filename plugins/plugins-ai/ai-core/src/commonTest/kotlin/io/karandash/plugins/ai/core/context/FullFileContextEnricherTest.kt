package io.karandash.plugins.ai.core.context

import io.karandash.api.ai.BlockBudget
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullFileContextEnricherTest {
    private val enricher = FullFileContextEnricher()

    private fun mrWith(vararg changes: FileChange): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            author = User(username = "u", name = "U"),
            sourceBranch = "feat",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun change(
        path: String,
        newContent: String?,
        additions: Int = 5
    ): FileChange =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            additions = additions,
            diffContent = "@@ -1 +1,2 @@\n+added",
            newContent = newContent,
        )

    private fun enabledConfig(
        maxFiles: Int = 5,
        maxFileLines: Int = 500,
        layout: String = "whole",
    ): JsonElement =
        buildJsonObject {
            putJsonObject("contextQuality") {
                putJsonObject("fullFileContext") {
                    put("enabled", true)
                    put("maxFiles", maxFiles)
                    put("maxFileLines", maxFileLines)
                    put("layout", layout)
                }
            }
        }

    @Test
    fun `disabled by default`() =
        runTest {
            val result = enricher.provideBlock(
                mr = mrWith(change("src/Main.kt", "fun main() = Unit")),
                changes = mrWith(change("src/Main.kt", "fun main() = Unit")).changes,
                config = JsonNull,
            )
            assertNull(result)
        }

    @Test
    fun `enabled block emits content for in-budget file`() =
        runTest {
            val mr = mrWith(change("src/Main.kt", "fun main() {\n    println()\n}"))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig())
            assertNotNull(result)
            assertEquals(FullFileContextEnricher.BLOCK_PRIORITY, result.priority)
            assertTrue(result.content.contains("src/Main.kt"))
            assertTrue(result.content.contains("fun main"))
        }

    @Test
    fun `sensitive file is excluded`() =
        runTest {
            val mr = mrWith(change(".env", "DB_PASSWORD=supersecret"))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig())
            assertNull(result)
        }

    @Test
    fun `oversized file is excluded`() =
        runTest {
            val big = (1..600).joinToString("\n") { "line $it" }
            val mr = mrWith(change("src/Huge.kt", big))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(maxFileLines = 500))
            assertNull(result)
        }

    @Test
    fun `null newContent excludes the file`() =
        runTest {
            val mr = mrWith(change("src/NoContent.kt", newContent = null))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig())
            assertNull(result)
        }

    @Test
    fun `top-N selection enforced`() =
        runTest {
            val changes = (1..10).map { i ->
                change("src/F$i.kt", newContent = "class F$i", additions = i)
            }
            val mr = mrWith(*changes.toTypedArray())
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(maxFiles = 3))
            assertNotNull(result)
            // Three file markers present; others should be skipped.
            val fileMarkerCount = "### src/".toRegex().findAll(result.content).count()
            assertEquals(3, fileMarkerCount)
        }

    @Test
    fun `diff-only mode returns null`() =
        runTest {
            val mr = mrWith(change("src/Main.kt", "fun main() = Unit"))
            val diffOnly: JsonElement = Json.parseToJsonElement(
                """{"contextQuality":{"fullFileContext":{"enabled":true,"mode":"diff-only"}}}""",
            )
            val result = enricher.provideBlock(mr, mr.changes, diffOnly)
            assertNull(result)
        }

    @Test
    fun `kebab case config keys accepted`() =
        runTest {
            val mr = mrWith(change("src/Main.kt", "fun main() = Unit"))
            val kebab: JsonElement = Json.parseToJsonElement(
                """{"context-quality":{"full-file-context":{"enabled":true,"max-files":5,"max-file-lines":500}}}""",
            )
            val result = enricher.provideBlock(mr, mr.changes, kebab)
            assertNotNull(result)
        }

    @Test
    fun `files past the budget are left out whole and the cut is recorded`() =
        runTest {
            val small = change("src/Small.kt", "class Small", additions = 9)
            val big = change("src/Big.kt", (1..40).joinToString("\n") { "val big$it = $it" }, additions = 1)
            val mr = mrWith(small, big)
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(), BlockBudget(SMALL_BUDGET))

            assertNotNull(result)
            assertTrue("class Small" in result.content, "a file that fits must be sent")
            assertFalse("val big1 " in result.content, "a file past the budget must not be sent in part")
            assertTrue(result.content.length <= SMALL_BUDGET, "block exceeds its budget")
            assertNotNull(result.cutFromChars, "the cut must be recorded for the ledger")
        }

    @Test
    fun `no file within the budget gives an empty block that records the cut`() =
        runTest {
            val mr = mrWith(change("src/Big.kt", (1..40).joinToString("\n") { "val big$it = $it" }))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(), BlockBudget(SMALL_BUDGET))

            assertNotNull(result)
            assertTrue(result.content.isEmpty(), "nothing fits, nothing is sent")
            assertNotNull(result.cutFromChars, "the cut must be recorded for the ledger")
        }

    @Test
    fun `files within the budget are sent whole with no cut`() =
        runTest {
            val mr = mrWith(change("src/A.kt", "class A"), change("src/B.kt", "class B"))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(), BlockBudget(LARGE_BUDGET))

            assertNotNull(result)
            assertTrue("class A" in result.content && "class B" in result.content)
            assertNull(result.cutFromChars, "an uncut block must not be recorded as cut")
        }

    private fun bigFile() = (1..400).joinToString("\n") { "line $it" }

    private fun hunkChange(
        path: String,
        lineNo: Int,
        diffLines: Int = 1,
    ): FileChange =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            additions = diffLines,
            diffContent = "@@ -$lineNo,0 +$lineNo,$diffLines @@\n" + (lineNo until lineNo + diffLines).joinToString("\n") { "+line $it" },
            newContent = bigFile(),
        )

    @Test
    fun `windowed layout sends numbered windows around the change and not the file head`() =
        runTest {
            val mr = mrWith(hunkChange("src/Big.kt", 300))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(layout = "windowed"), BlockBudget(LARGE_BUDGET))

            assertNotNull(result)
            assertTrue("300| line 300" in result.content)
            assertFalse("line 2\n" in result.content, "far head lines must not be sent")
            assertTrue("..." in result.content, "skipped lines must be marked")
            assertNull(result.cutFromChars)
        }

    @Test
    fun `windowed layout under a tight budget keeps the lines nearest the change`() =
        runTest {
            val mr = mrWith(hunkChange("src/Big.kt", 300, diffLines = 40))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(layout = "windowed"), BlockBudget(400))

            assertNotNull(result)
            assertTrue(result.content.length <= 400, "block exceeds its budget")
            assertTrue("300| line 300" in result.content, "the changed line must survive the cut: ${result.content}")
            assertNotNull(result.cutFromChars)
        }

    @Test
    fun `windowed layout takes a file over max-file-lines that whole layout leaves out`() =
        runTest {
            val mr = mrWith(hunkChange("src/Big.kt", 10))
            val whole = enricher.provideBlock(mr, mr.changes, enabledConfig(maxFileLines = 100, layout = "whole"))
            val windowed = enricher.provideBlock(mr, mr.changes, enabledConfig(maxFileLines = 100, layout = "windowed"))

            assertNull(whole)
            assertNotNull(windowed)
            assertTrue("10| line 10" in windowed.content)
        }

    @Test
    fun `windowed layout leaves import and package lines out unless stripImports is false`() =
        runTest {
            val mr = mrWith(change("src/Main.kt", "package app\nimport io.example.Foo\n\nfun main() = Foo()"))
            val stripped = enricher.provideBlock(mr, mr.changes, enabledConfig(layout = "windowed"))
            val kept = enricher.provideBlock(mr, mr.changes, withStripImports(enabledConfig(layout = "windowed"), stripImports = false))
            val whole = enricher.provideBlock(mr, mr.changes, enabledConfig(layout = "whole"))

            assertNotNull(stripped)
            assertTrue("fun main() = Foo()" in stripped.content)
            assertFalse("import io.example.Foo" in stripped.content, stripped.content)
            assertFalse("package app" in stripped.content, stripped.content)
            assertTrue("import io.example.Foo" in checkNotNull(kept).content)
            assertTrue("import io.example.Foo" in checkNotNull(whole).content)
        }

    @Test
    fun `windowed windows of a big file skip import lines`() =
        runTest {
            val content = (1..300).joinToString("\n") { if (it in 295..299) "import io.example.C$it" else "line $it" }
            val mr = mrWith(hunkChange("src/Big.kt", 300).copy(newContent = content))
            val result = enricher.provideBlock(mr, mr.changes, enabledConfig(layout = "windowed"), BlockBudget(LARGE_BUDGET))

            assertNotNull(result)
            assertTrue("300| line 300" in result.content, result.content)
            assertFalse("import io.example" in result.content, result.content)
        }

    private fun withStripImports(
        config: JsonElement,
        stripImports: Boolean,
    ): JsonElement =
        JsonObject(
            config.jsonObject + ("context" to buildJsonObject { put("stripImports", stripImports) }),
        )

    private companion object {
        const val SMALL_BUDGET = 120
        const val LARGE_BUDGET = 20_000
    }
}
