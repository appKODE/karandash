package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyValidationRuleModuleTest {
    private fun change(
        path: String,
        vararg added: String,
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        diffContent = (listOf("--- a/$path", "+++ b/$path") + added.map { "+$it" }).joinToString("\n"),
    )

    private fun parsed(
        path: String,
        vararg added: String,
    ) = DependencyFileParser.parseAddedDependencies(path, change(path, *added).diffContent)

    @Test
    fun `module validates the whole MR and a single file through the json config`() =
        runTest {
            val module = DependencyValidationRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                JsonObject(
                    mapOf(
                        "warnOnUnpinnedVersions" to JsonPrimitive(false),
                        "blockedPatterns" to JsonArray(listOf(JsonPrimitive("left-pad"))),
                    ),
                )
            val bad = change("web/package.json", "\"left-pad\": \"^1.0.0\"")
            val fine = change("README.md", "\"left-pad\": \"1.0.0\"")
            val mr = createTestMergeRequest(changes = listOf(bad, fine))

            val finding = module.validate(DependencyValidationRuleModule.RULE_ID, mr, config).single()
            assertEquals("Blocked dependency pattern 'left-pad' matched by 'left-pad:^1.0.0' in web/package.json", finding.message)
            assertEquals(
                listOf(finding.message),
                module.validateFile(DependencyValidationRuleModule.RULE_ID, bad, mr, config).map { it.message }
            )
            assertTrue(module.validateFile(DependencyValidationRuleModule.RULE_ID, fine, mr, config).isEmpty())
        }

    @Test
    fun `parsers read each supported file format`() {
        assertEquals(
            listOf(DependencyEntry("serde", "1.0.1"), DependencyEntry("rand", "0.8")),
            parsed("Cargo.toml", "[dependencies]", "# note", "serde = { version = \"1.0.1\", features = [\"derive\"] }", "rand = \"0.8\""),
        )
        assertEquals(
            listOf(DependencyEntry("kotlin", "2.1.0")),
            parsed("gradle/libs.versions.toml", "[versions]", "# pinned", "kotlin = \"2.1.0\"", "x = { module = \"a:b\" }"),
        )
        assertEquals(
            listOf(DependencyEntry("requests", "==2.31.0")),
            parsed("requirements.txt", "# comment", "", "requests==2.31.0", "-r other.txt"),
        )
        assertEquals(
            listOf(DependencyEntry("org.a:b", "1.0")),
            parsed("build.gradle", "// implementation 'x:y:9'", "implementation 'org.a:b:1.0'", "apply plugin: 'java'"),
        )
        assertEquals(
            listOf(DependencyEntry("react", "18.2.0")),
            parsed("package.json", "\"name\": \"app\"", "\"version\": \"1.0.0\"", "\"react\": \"18.2.0\"", "\"private\": true"),
        )
        assertTrue(parsed("pom.xml", "<version>1</version>").isEmpty())
        assertTrue(DependencyFileParser.parseAddedDependencies("go.mod", null).isEmpty())
        assertTrue(DependencyFileParser.parseAddedDependencies("go.mod", "  ").isEmpty())
    }

    @Test
    fun `go mod single line require is parsed like a require block entry`() {
        assertEquals(
            listOf(DependencyEntry("github.com/a/b", "1.2.3"), DependencyEntry("github.com/c/d", "0.4.0")),
            parsed("go.mod", "require github.com/a/b v1.2.3", "\tgithub.com/c/d v0.4.0 // indirect", "go 1.21"),
        )
    }

    @Test
    fun `blocked patterns match by name version prefix and gradle coordinates`() {
        fun blocked(
            pattern: String,
            entry: String,
        ) = DependencyValidator(DependencyValidationConfig(warnOnUnpinnedVersions = false, blockedPatterns = listOf(pattern)))
            .validate(createTestMergeRequest(changes = listOf(change("build.gradle.kts", entry))))
            .isNotEmpty()

        val log4j = "implementation(\"org.apache.logging.log4j:log4j-core:2.14.1\")"
        assertTrue(blocked("org.apache.logging.log4j:log4j-core:2.14", log4j))
        assertTrue(!blocked("org.apache.logging.log4j:log4j-core:2.17", log4j))
        assertTrue(!blocked("org.apache:log4j-core:2.14", log4j))
        assertTrue(!blocked("a:b:c:d", log4j))
        assertTrue(blocked("ORG.APACHE.LOGGING.LOG4J:LOG4J-CORE:", log4j))
    }

    @Test
    fun `unpinned detection covers wildcards and skips blank versions`() {
        val validator = DependencyValidator(DependencyValidationConfig())

        fun warns(version: String) =
            validator
                .validate(createTestMergeRequest(changes = listOf(change("package.json", "\"a\": \"$version\""))))
                .isNotEmpty()

        assertEquals(listOf(true, true, true, false, false), listOf("*", "X", "~1.2", "1.2.3", "file:x").map { warns(it) })
        assertTrue(
            DependencyValidator(DependencyValidationConfig(), unpinnedPrefixes = emptySet(), unpinnedValues = emptySet())
                .validate(createTestMergeRequest(changes = listOf(change("package.json", "\"a\": \"^1\""))))
                .isEmpty(),
        )
    }
}
