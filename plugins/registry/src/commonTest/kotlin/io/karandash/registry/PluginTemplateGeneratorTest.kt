package io.karandash.registry

import io.karandash.Version
import io.karandash.api.ModuleType
import io.karandash.platform.readText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTemplateGeneratorTest {
    private val generator = PluginTemplateGenerator()

    private fun tempDir(): Path =
        TestFiles.createTestTempDirectory("plugin-template").also {
            SystemFileSystem.createDirectories(it)
        }

    private fun generate(
        type: ModuleType,
        baseDir: Path = tempDir(),
    ): Path = generator.generateProject("my-check", type, TemplateConfig("my-check", "com.example.check", type), baseDir).getOrThrow()

    private fun Path.text(relative: String): String = Path(this, relative).readText()

    @Test
    fun `rule project has sources tests services and readme`() {
        val dir = generate(ModuleType.RULE)
        val pkg = "src/commonMain/kotlin/com/example/check"
        val source = dir.text("$pkg/MyCheckRuleModule.kt")
        assertTrue(source.contains("package com.example.check"))
        assertTrue(source.contains("class MyCheckRuleModule"))
        assertFalse(source.contains("{{"), "every placeholder is substituted")
        assertTrue(dir.text("src/commonTest/kotlin/com/example/check/MyCheckRuleModuleTest.kt").contains("MyCheckRuleModule"))
        assertEquals(
            "com.example.check.MyCheckRuleModule\n",
            dir.text("src/jvmMain/resources/META-INF/services/io.karandash.api.Module"),
        )
        assertTrue(dir.text("settings.gradle.kts").contains("my-check-plugin"))
        assertTrue(dir.text("build.gradle.kts").contains(Version.VERSION))
        val readme = dir.text("README.md")
        assertTrue(readme.contains("my-check-check:"))
        assertFalse(readme.contains("{{"))
        assertFalse(SystemFileSystem.exists(Path(dir, ".gitignore")), "git init is opt-in")
    }

    @Test
    fun `reporter project uses the reporter template and config block`() {
        val dir = generate(ModuleType.REPORTER)
        assertTrue(dir.text("src/commonMain/kotlin/com/example/check/MyCheckReporterModule.kt").contains("MyCheckReporterModule"))
        assertTrue(dir.text("README.md").contains("reporters:"))
    }

    @Test
    fun `platform service project uses the provider template and config block`() {
        val dir = generate(ModuleType.PLATFORM_SERVICE)
        val source = dir.text("src/commonMain/kotlin/com/example/check/MyCheckPlatformServiceModule.kt")
        assertTrue(source.contains("postComment"))
        assertTrue(dir.text("README.md").contains("my-check-platform-service:"))
    }

    @Test
    fun `existing project directory is a failure`() {
        val base = tempDir()
        generate(ModuleType.RULE, base)
        val second = generator.generateProject("my-check", ModuleType.RULE, TemplateConfig("my-check", "p", ModuleType.RULE), base)
        assertTrue(
            second
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("already exists")
        )
    }

    @Test
    fun `type mismatch between argument and config is a failure`() {
        val result = generator.generateProject("x", ModuleType.RULE, TemplateConfig("x", "p", ModuleType.REPORTER), tempDir())
        assertTrue(
            result
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("type mismatch")
        )
    }

    @Test
    fun `type without a template is a failure`() {
        val result = generator.generateSourceFile(tempDir(), TemplateConfig("x", "p", ModuleType.PROFILE))
        assertTrue(result.isFailure)
    }

    @Test
    fun `plugin names must be lowercase kebab case`() {
        assertTrue(generator.validatePluginName("my-custom-rule2").isSuccess)
        assertTrue(
            generator
                .validatePluginName("")
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("empty")
        )
        assertTrue(generator.validatePluginName("My_Rule").isFailure)
        assertTrue(generator.validatePluginName("trailing-").isFailure)
    }

    @Test
    fun `toCamelCase splits on hyphens and underscores`() {
        assertEquals("MyCustomRule", generator.toCamelCase("my-custom_rule"))
    }

    @Test
    fun `toCamelCase drops empty segments from doubled or edge separators`() {
        assertEquals("MyRule", generator.toCamelCase("-my--rule_"))
        assertEquals("", generator.toCamelCase(""))
    }

    @Test
    fun `git init runs through outputOrThrow and fails on a missing directory`() {
        val dir = tempDir()
        generator.initGitRepository(dir).getOrThrow()
        assertTrue(SystemFileSystem.exists(Path(dir, ".git")))
        assertTrue(generator.initGitRepository(Path(dir, "missing/nested")).isFailure)
    }

    /** The SDK is published to Maven Central as ru.kode.karandash:sdk; the scaffold needs no token. */
    @Test
    fun `generated build resolves the SDK from Maven Central without a token`() {
        val dir = generate(ModuleType.RULE)
        val build = dir.text("build.gradle.kts")
        assertTrue(build.contains("mavenCentral()"), build)
        assertTrue(build.contains("ru.kode.karandash:sdk:"), build)
        listOf("Private-Token", "HttpHeaderCredentials").forEach { assertFalse(build.contains(it), build) }
        val readme = dir.text("README.md")
        assertTrue(readme.contains("Maven Central"), readme)
        listOf("Private-Token", "read_api").forEach { assertFalse(readme.contains(it), readme) }
    }
}
