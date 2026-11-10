package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyRegressionTest {
    private val validator = DependencyValidator(DependencyValidationConfig(warnOnUnpinnedVersions = true))

    private fun messages(
        path: String,
        diff: String,
    ) = validator
        .validate(
            createTestMergeRequest(
                changes = listOf(FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff)),
            ),
        ).map { it.message }

    @Test
    fun `package json only reads dependency blocks`() {
        val diff = """
            @@ -1,10 +1,14 @@
             {
              "engines": {
            +    "node": ">=18"
              },
              "scripts": {
            +    "build": "tsc ^5"
              },
              "dependencies": {
            +    "lodash": "^4.17.21"
              }
            }
        """.trimIndent()
        val found = messages("package.json", diff)
        assertEquals(1, found.size, found.toString())
        assertTrue(found.single().contains("lodash"))
    }

    @Test
    fun `plus x and variable versions are unpinned`() {
        val gradle = """
            +  implementation("org.foo:bar:1.+")
            +  implementation("org.foo:baz:1.x")
            +  implementation("org.foo:qux:${'$'}quxVersion")
        """.trimIndent()
        val found = messages("build.gradle.kts", gradle)
        assertEquals(3, found.size, found.toString())
    }

    @Test
    fun `libs versions toml resolves version ref and ignores other sections`() {
        val diff = """
            @@ -1,8 +1,10 @@
             [versions]
            +okhttp = "4.+"
             [libraries]
            +okhttp-lib = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
             [plugins]
            +kotlin = "1.9.0"
        """.trimIndent()
        val found = messages("gradle/libs.versions.toml", diff)
        assertEquals(2, found.size, found.toString())
        assertTrue(found.any { it.contains("com.squareup.okhttp3:okhttp") }, found.toString())
        assertTrue(found.none { it.contains("'kotlin'") }, found.toString())
    }

    @Test
    fun `libs versions toml shorthand group artifact version is parsed`() {
        val diff = """
            @@ -1,4 +1,5 @@
             [libraries]
            +okhttp-lib = "com.squareup.okhttp3:okhttp:4.+"
            +pinned = "io.ktor:ktor-client-core:3.0.0"
        """.trimIndent()
        val found = messages("gradle/libs.versions.toml", diff)
        assertEquals(1, found.size, found.toString())
        assertTrue(found.single().contains("com.squareup.okhttp3:okhttp"), found.toString())
    }
}
