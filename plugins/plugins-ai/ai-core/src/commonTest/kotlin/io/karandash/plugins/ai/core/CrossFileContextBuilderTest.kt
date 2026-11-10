package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossFileContextBuilderTest {
    private fun change(
        path: String,
        diff: String?,
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = diff,
    )

    // region import extraction

    @Test
    fun `kotlin imports extracted from diff`() {
        val diff = """
            @@ -1,3 +1,4 @@
             package io.example
            +import io.karandash.models.FileChange
            +import io.karandash.plugins.ai.core.DiffDeltaCalculator
             class Foo
        """.trimIndent()
        val imports = CrossFileContextBuilder.extractImports(diff)
        assertContains(imports, "io.karandash.models.FileChange")
        assertContains(imports, "io.karandash.plugins.ai.core.DiffDeltaCalculator")
    }

    @Test
    fun `typescript named imports extracted from diff`() {
        val diff = """
            +import { Foo, Bar } from './foo-module'
            +import DefaultThing from '../other/thing'
        """.trimIndent()
        val imports = CrossFileContextBuilder.extractImports(diff)
        assertContains(imports, "./foo-module")
        assertContains(imports, "../other/thing")
    }

    @Test
    fun `python imports extracted from diff`() {
        val diff = """
            +from os.path import join
            +import sys
            +from collections import OrderedDict
        """.trimIndent()
        val imports = CrossFileContextBuilder.extractImports(diff)
        assertContains(imports, "os.path")
        assertContains(imports, "sys")
        assertContains(imports, "collections")
    }

    @Test
    fun `go single import extracted from diff`() {
        val diff = """
            +import "github.com/foo/bar"
        """.trimIndent()
        val imports = CrossFileContextBuilder.extractImports(diff)
        assertContains(imports, "github.com/foo/bar")
    }

    @Test
    fun `imports on unchanged and removed lines are also captured`() {
        val diff = """
             import io.karandash.models.MergeRequest
            -import io.karandash.models.OldModel
        """.trimIndent()
        val imports = CrossFileContextBuilder.extractImports(diff)
        assertContains(imports, "io.karandash.models.MergeRequest")
        assertContains(imports, "io.karandash.models.OldModel")
    }

    // endregion

    // region signature extraction

    @Test
    fun `kotlin class and fun signatures extracted from added lines`() {
        val diff = """
            +data class UserConfig(val name: String)
            +fun processRequest(req: Request): Response
            +suspend fun fetchData(url: String): String
            +interface Validator
        """.trimIndent()
        val sigs = CrossFileContextBuilder.extractSignatures(diff)
        assertTrue(sigs.any { it.contains("UserConfig") }, "expected UserConfig in $sigs")
        assertTrue(sigs.any { it.contains("processRequest") }, "expected processRequest in $sigs")
        assertTrue(sigs.any { it.contains("fetchData") }, "expected fetchData in $sigs")
        assertTrue(sigs.any { it.contains("Validator") }, "expected Validator in $sigs")
    }

    @Test
    fun `python def signatures extracted from added lines`() {
        val diff = """
            +def calculate_total(items: list) -> float:
            +    pass
            +async def fetch_user(user_id: int):
            +    pass
        """.trimIndent()
        val sigs = CrossFileContextBuilder.extractSignatures(diff)
        assertTrue(sigs.any { it.contains("calculate_total") }, "expected calculate_total in $sigs")
        assertTrue(sigs.any { it.contains("fetch_user") }, "expected fetch_user in $sigs")
    }

    @Test
    fun `typescript function signatures extracted from added lines`() {
        val diff = """
            +export function validateInput(data: unknown): boolean {
            +export async function sendRequest(url: string): Promise<void> {
            +export default class ApiClient {
        """.trimIndent()
        val sigs = CrossFileContextBuilder.extractSignatures(diff)
        assertTrue(sigs.any { it.contains("validateInput") }, "expected validateInput in $sigs")
        assertTrue(sigs.any { it.contains("sendRequest") }, "expected sendRequest in $sigs")
        assertTrue(sigs.any { it.contains("ApiClient") }, "expected ApiClient in $sigs")
    }

    @Test
    fun `go func signatures extracted from added lines`() {
        val diff = """
            +func NewHandler(cfg Config) *Handler {
            +type Repository struct {
        """.trimIndent()
        val sigs = CrossFileContextBuilder.extractSignatures(diff)
        assertTrue(sigs.any { it.contains("NewHandler") }, "expected NewHandler in $sigs")
        assertTrue(sigs.any { it.contains("Repository") }, "expected Repository in $sigs")
    }

    @Test
    fun `signatures on removed or context lines are not extracted`() {
        val diff = """
            -fun oldFunction(x: Int): String
             fun contextFunction(y: Int): String
        """.trimIndent()
        val sigs = CrossFileContextBuilder.extractSignatures(diff)
        assertTrue(sigs.none { it.contains("oldFunction") }, "oldFunction should not be extracted")
        assertTrue(sigs.none { it.contains("contextFunction") }, "contextFunction should not be extracted")
    }

    // endregion

    // region buildContext

    @Test
    fun `buildContext returns null for empty changes list`() {
        assertNull(CrossFileContextBuilder.buildContext(emptyList()))
    }

    @Test
    fun `buildContext returns null when all diffs are null`() {
        val changes = listOf(
            change("src/Foo.kt", null),
            change("src/Bar.kt", null),
        )
        assertNull(CrossFileContextBuilder.buildContext(changes))
    }

    @Test
    fun `buildContext returns null when all diffs are blank`() {
        val changes = listOf(change("src/Foo.kt", "   "))
        assertNull(CrossFileContextBuilder.buildContext(changes))
    }

    @Test
    fun `buildContext returns null when no imports and no signatures found`() {
        val diff = "@@ -1,1 +1,2 @@\n val x = 1"
        val changes = listOf(change("src/Foo.kt", diff))
        assertNull(CrossFileContextBuilder.buildContext(changes))
    }

    @Test
    fun `buildContext includes imports when present`() {
        val diff = "+import io.karandash.models.MergeRequest\n+class Foo"
        val changes = listOf(change("src/Foo.kt", diff))
        val ctx = CrossFileContextBuilder.buildContext(changes)
        assertNotNull(ctx)
        assertContains(ctx, "io.karandash.models.MergeRequest")
        assertContains(ctx, "src/Foo.kt")
    }

    @Test
    fun `buildContext without the import graph leaves imports out but keeps signatures`() {
        val diff = "+import io.karandash.models.MergeRequest\n+fun doSomething(x: Int): String"
        val ctx = CrossFileContextBuilder.buildContext(listOf(change("src/Foo.kt", diff)), importGraph = false)
        assertNotNull(ctx)
        assertContains(ctx, "doSomething")
        assertTrue("io.karandash.models.MergeRequest" !in ctx, ctx)
    }

    @Test
    fun `buildContext includes signatures when present`() {
        val diff = "+fun doSomething(x: Int): String"
        val changes = listOf(change("src/Foo.kt", diff))
        val ctx = CrossFileContextBuilder.buildContext(changes)
        assertNotNull(ctx)
        assertContains(ctx, "doSomething")
    }

    @Test
    fun `buildContext respects max chars limit`() {
        val bigDiff = (1..200).joinToString("\n") { "+import io.example.Package$it" }
        val changes = listOf(change("src/Big.kt", bigDiff))
        val maxChars = 100
        val ctx = CrossFileContextBuilder.buildContext(changes, maxChars = maxChars)
        assertNotNull(ctx)
        assertTrue(ctx.length <= maxChars, "expected at most $maxChars chars but got ${ctx.length}")
    }

    @Test
    fun `buildContext marks changed files with changed annotation`() {
        val diff = "+import io.example.Foo"
        val changes = listOf(change("src/Foo.kt", diff))
        val ctx = CrossFileContextBuilder.buildContext(changes)
        assertNotNull(ctx)
        assertContains(ctx, "(changed)")
    }

    @Test
    fun `buildContext handles multiple files with cross-references`() {
        val diffA = "+import io.example.Bar\n+class Foo"
        val diffB = "+import io.example.Foo\n+class Bar"
        val changes = listOf(
            change("src/Foo.kt", diffA),
            change("src/Bar.kt", diffB),
        )
        val ctx = CrossFileContextBuilder.buildContext(changes)
        assertNotNull(ctx)
        assertContains(ctx, "src/Foo.kt")
        assertContains(ctx, "src/Bar.kt")
        assertContains(ctx, "io.example.Bar")
        assertContains(ctx, "io.example.Foo")
    }

    @Test
    fun `buildContext no imports returns context with only signatures`() {
        val diff = "+fun standalone(x: Int): Int"
        val changes = listOf(change("src/Util.kt", diff))
        val ctx = CrossFileContextBuilder.buildContext(changes)
        assertNotNull(ctx)
        assertContains(ctx, "standalone")
    }

    // endregion

    // region default constant

    @Test
    fun `DEFAULT_MAX_CONTEXT_CHARS is a ceiling above the auto context budget`() {
        assertEquals(40_000, CrossFileContextBuilder.DEFAULT_MAX_CONTEXT_CHARS)
    }

    // endregion

    // region C and C++

    @Test
    fun `C and C++ includes and function definitions are extracted only for C family files`() {
        val diff = "+#include \"net/socket.h\"\n+#include <vector>\n+static int Pool::acquire(int n) const {\n" +
            "+struct Buffer\n+    if (ready(n)) {\n+    return grow(n);"

        assertEquals(listOf("net/socket.h", "vector"), CrossFileContextBuilder.extractImports(diff))
        assertEquals(
            listOf("static int Pool::acquire(int n) const", "struct Buffer"),
            CrossFileContextBuilder.extractSignatures(diff, cFamily = true),
        )
        assertEquals(emptyList(), CrossFileContextBuilder.extractSignatures(diff))
        assertEquals(
            listOf("src/net/socket.h", "net/socket.h"),
            DependencyContextBuilder.importCandidates("src/pool.cpp", "net/socket.h", emptyList()),
        )
    }

    // endregion
}
