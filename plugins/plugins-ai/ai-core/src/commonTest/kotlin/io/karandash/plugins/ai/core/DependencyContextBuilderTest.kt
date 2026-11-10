package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.config.CrossFileContextConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DependencyContextBuilderTest {
    private val kotlinChange = FileChange(
        path = "app/src/main/kotlin/com/acme/Service.kt",
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = "@@ -1 +1 @@\n import com.acme.repo.UserRepo\n+import com.acme.util.formatName\n",
    )

    private val repo = mapOf(
        "app/src/main/kotlin/com/acme/repo/UserRepo.kt" to
            "package com.acme.repo\n\nclass UserRepo {\n    fun find(id: String): User? = null\n    private val token = \"x\"\n}\n",
        "app/src/main/kotlin/com/acme/util.kt" to "fun formatName(n: String) = n",
        "app/src/test/kotlin/com/acme/ServiceTest.kt" to "class ServiceTest",
        "app/src/main/kotlin/com/acme/repo/.env" to "SECRET=1",
    )

    private fun build(
        config: CrossFileContextConfig = CrossFileContextConfig(),
        maxChars: Int = 10_000,
    ) = DependencyContextBuilder.build(listOf(kotlinChange), config, maxChars) { repo[it] }

    @Test
    fun `imports and tests resolve to repository files with signatures only`() {
        val section = assertNotNull(build())

        assertTrue(section.startsWith(DependencyContextBuilder.HEADING))
        assertTrue(section.contains("### app/src/main/kotlin/com/acme/repo/UserRepo.kt"))
        assertTrue(section.contains("fun find(id: String): User? = null"))
        assertFalse(section.contains("private val token"), section)
        assertTrue(section.contains("### app/src/test/kotlin/com/acme/ServiceTest.kt"))
    }

    @Test
    fun `captureSignatures false sends the full redacted text`() {
        val section = assertNotNull(build(CrossFileContextConfig(captureSignatures = false)))
        assertTrue(section.contains("package com.acme.repo"))
        assertFalse(section.contains("token = \"x\""), section)
    }

    @Test
    fun `maxContextFiles and maxChars cap the section`() {
        val one = assertNotNull(build(CrossFileContextConfig(maxContextFiles = 1)))
        assertEquals(1, Regex("### ").findAll(one).count())
        assertEquals(40, assertNotNull(build(maxChars = 40)).length)
    }

    @Test
    fun `includeImports and includeTests false leave nothing`() {
        assertNull(build(CrossFileContextConfig(includeImports = false, includeTests = false)))
    }

    @Test
    fun `includeImports false keeps only the test files`() {
        val section = assertNotNull(build(CrossFileContextConfig(includeImports = false)))

        assertTrue(section.contains("### app/src/test/kotlin/com/acme/ServiceTest.kt"), section)
        assertFalse(section.contains("UserRepo.kt"), section)
    }

    @Test
    fun `includeTests false keeps only the imported files`() {
        val section = assertNotNull(build(CrossFileContextConfig(includeTests = false)))

        assertTrue(section.contains("### app/src/main/kotlin/com/acme/repo/UserRepo.kt"), section)
        assertFalse(section.contains("ServiceTest.kt"), section)
    }

    @Test
    fun `relative ts imports stay inside the repository`() {
        val candidates = DependencyContextBuilder.importCandidates("web/src/app.ts", "../../../../etc/passwd", emptyList())
        assertTrue(candidates.all { it.startsWith("etc/passwd") }, candidates.toString())
        assertTrue("web/src/lib/api.ts" in DependencyContextBuilder.importCandidates("web/src/app.ts", "./lib/api", emptyList()))
    }

    @Test
    fun `python modules resolve to py files and packages`() {
        assertEquals(
            listOf("pkg/models.py", "pkg/models/__init__.py"),
            DependencyContextBuilder.importCandidates("pkg/views.py", "pkg.models", emptyList()),
        )
    }

    @Test
    fun `an include outside the hunks is found in the full file`() {
        val cpp = FileChange(
            path = "search/ranker.cpp",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            deletions = 0,
            diffContent = "@@ -179 +179 @@\n-  return a;\n+  return b;\n",
            newContent = "#include \"search/token_slice.hpp\"\n\nint f() { return b; }\n",
        )

        val section = DependencyContextBuilder.build(listOf(cpp), CrossFileContextConfig(), 10_000) { path ->
            "class TokenSliceNoCategories {};".takeIf { path == "search/token_slice.hpp" }
        }

        assertTrue(assertNotNull(section).contains("### search/token_slice.hpp"), section)
    }

    @Test
    fun `fetchRelated stops at maxContextFiles and never asks for sensitive files`() =
        runTest {
            val asked = mutableListOf<String>()
            val fetched = DependencyContextBuilder.fetchRelated(listOf(kotlinChange), CrossFileContextConfig(maxContextFiles = 1)) {
                asked += it
                repo[it]
            }

            // `formatName` is imported on a changed line, so its file is read first
            assertEquals(listOf("app/src/main/kotlin/com/acme/util.kt"), fetched.keys.toList())
            assertEquals(fetched.keys.last(), asked.last())
            assertTrue(asked.none { SensitiveFileFilter.isSensitive(it) }, asked.toString())
        }

    @Test
    fun `an import used on a changed line is read before an unused one`() {
        val change = kotlinChange.copy(
            newContent = "import com.acme.repo.UserRepo\nimport com.acme.util.Clock\n\nfun f() = Clock.now()\n",
            diffContent = "@@ -1,3 +1,4 @@\n import com.acme.repo.UserRepo\n import com.acme.util.Clock\n \n+fun f() = Clock.now()\n",
        )
        val files = repo + ("app/src/main/kotlin/com/acme/util/Clock.kt" to "object Clock { fun now() = 0 }")
        val config = CrossFileContextConfig(includeTests = false, maxContextFiles = 1)

        val section = assertNotNull(DependencyContextBuilder.build(listOf(change), config, 10_000) { files[it] })

        assertTrue("util/Clock.kt" in section, section)
        assertFalse("UserRepo" in section, section)
    }

    @Test
    fun `library imports and system includes spend no lookup`() {
        val kotlin = kotlinChange.copy(
            newContent = "package com.acme\n\nimport io.ktor.client.HttpClient\nimport kotlinx.coroutines.flow.Flow\n" +
                "import com.acme.util.Clock\n",
        )
        val cpp = FileChange(
            path = "search/ranker.cpp",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            deletions = 0,
            newContent = "#include <vector>\n#include <boost/optional.hpp>\n#include \"search/token_slice.hpp\"\n",
        )

        val paths = DependencyContextBuilder.relatedPaths(listOf(kotlin, cpp), CrossFileContextConfig(includeTests = false))

        assertTrue(paths.none { "ktor" in it || "kotlinx" in it || "vector" in it }, paths.toString())
        assertTrue("app/src/main/kotlin/com/acme/util/Clock.kt" in paths, paths.toString())
        assertTrue("search/token_slice.hpp" in paths, paths.toString())
    }

    @Test
    fun `an import from an unchanged module resolves under the module named by its package`() {
        val change = FileChange(
            path = "android/feature/app/data/src/main/kotlin/com/acme/feature/app/data/Repo.kt",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            deletions = 0,
            newContent = "package com.acme.feature.app.data\n\nimport com.acme.core.data.network.HttpClient.Builder\n",
        )

        val paths = DependencyContextBuilder.relatedPaths(listOf(change), CrossFileContextConfig(includeTests = false))

        assertTrue("android/core/data/src/main/kotlin/com/acme/core/data/network/HttpClient.kt" in paths, paths.toString())
        assertTrue(paths.size <= 3, paths.toString())
    }

    @Test
    fun `a KMP import resolves under the commonMain source set of the module named by its package`() {
        val change = FileChange(
            path = "core/feature/src/commonTest/kotlin/com/acme/core/feature/RepoTest.kt",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            deletions = 0,
            newContent = "package com.acme.core.feature\n\nimport com.acme.core.data.Store\n",
        )
        val main = change.copy(path = "core/feature/src/commonMain/kotlin/com/acme/core/feature/Repo.kt")

        val paths = DependencyContextBuilder.relatedPaths(listOf(change, main), CrossFileContextConfig(includeTests = false))

        assertEquals("core/data/src/commonMain/kotlin/com/acme/core/data/Store.kt", paths.first(), paths.toString())
    }
}
