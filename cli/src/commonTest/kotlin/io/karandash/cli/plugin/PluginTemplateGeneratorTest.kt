package io.karandash.cli.plugin

import io.karandash.Version
import io.karandash.api.ModuleType
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.createTestTempDirectory
import io.karandash.cli.deleteTestDirectoryRecursively
import io.karandash.registry.PluginTemplateGenerator
import io.karandash.registry.TemplateConfig
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTemplateGeneratorTest {
    private lateinit var generator: PluginTemplateGenerator
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        generator = PluginTemplateGenerator()
        tempDir = createTestTempDirectory("plugin-gen-test")
    }

    @AfterTest
    fun teardown() {
        deleteTestDirectoryRecursively(tempDir)
    }

    // --- validatePluginName ---

    @Test
    fun `validatePluginName accepts valid names`() {
        assertTrue(generator.validatePluginName("my-rule").isSuccess)
        assertTrue(generator.validatePluginName("simple").isSuccess)
        assertTrue(generator.validatePluginName("my-custom-rule").isSuccess)
        assertTrue(generator.validatePluginName("rule123").isSuccess)
    }

    @Test
    fun `validatePluginName rejects empty name`() {
        assertTrue(generator.validatePluginName("").isFailure)
    }

    @Test
    fun `validatePluginName rejects uppercase`() {
        assertTrue(generator.validatePluginName("MyRule").isFailure)
    }

    @Test
    fun `validatePluginName rejects leading hyphen`() {
        assertTrue(generator.validatePluginName("-my-rule").isFailure)
    }

    @Test
    fun `validatePluginName rejects trailing hyphen`() {
        assertTrue(generator.validatePluginName("my-rule-").isFailure)
    }

    @Test
    fun `validatePluginName rejects spaces`() {
        assertTrue(generator.validatePluginName("my rule").isFailure)
    }

    // --- toCamelCase ---

    @Test
    fun `toCamelCase converts hyphenated name`() {
        assertEquals("MyCustomRule", generator.toCamelCase("my-custom-rule"))
    }

    @Test
    fun `toCamelCase converts single word`() {
        assertEquals("Simple", generator.toCamelCase("simple"))
    }

    // --- generateProject for RULE type ---

    @Test
    fun `generateProject creates rule plugin directory structure`() {
        val config = TemplateConfig(
            name = "test-rule",
            packageName = "io.example.testrule",
            type = ModuleType.RULE,
            initGit = false
        )

        val result = generator.generateProject("test-rule", ModuleType.RULE, config, tempDir)
        assertTrue(result.isSuccess)

        val projectDir = result.getOrThrow()
        assertTrue(TestFileHelpers.exists(projectDir))
        assertTrue(TestFileHelpers.exists(Path(projectDir, "build.gradle.kts")))
        assertTrue(TestFileHelpers.exists(Path(projectDir, "settings.gradle.kts")))
        assertTrue(TestFileHelpers.exists(Path(projectDir, "README.md")))
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/commonMain/kotlin/io/example/testrule/TestRuleRuleModule.kt"
                )
            )
        )
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/commonTest/kotlin/io/example/testrule/TestRuleRuleModuleTest.kt"
                )
            )
        )
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/jvmMain/resources/META-INF/services/io.karandash.api.Module"
                )
            )
        )
    }

    // --- generateProject for REPORTER type ---

    @Test
    fun `generateProject creates reporter plugin`() {
        val config = TemplateConfig(
            name = "my-format",
            packageName = "io.example.myformat",
            type = ModuleType.REPORTER,
            initGit = false
        )

        val result = generator.generateProject("my-format", ModuleType.REPORTER, config, tempDir)
        assertTrue(result.isSuccess)

        val projectDir = result.getOrThrow()
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/commonMain/kotlin/io/example/myformat/MyFormatReporterModule.kt"
                )
            )
        )

        val sourceContent = TestFileHelpers.readFile(
            Path(projectDir, "src/commonMain/kotlin/io/example/myformat/MyFormatReporterModule.kt")
        )
        assertTrue(sourceContent.contains("class MyFormatReporterModule : AbstractFormatModule()"))
        assertTrue(sourceContent.contains("my-format-reporter"))
        // `reporters: { my-format: true }` is normalized to `myFormat`, so the format id must be camelCase.
        assertTrue(sourceContent.contains("override val formatId: String = \"myFormat\""), sourceContent)
    }

    // --- generateProject for PROVIDER type ---

    @Test
    fun `generateProject creates provider plugin`() {
        val config = TemplateConfig(
            name = "my-scm",
            packageName = "io.example.myscm",
            type = ModuleType.PLATFORM_SERVICE,
            initGit = false
        )

        val result = generator.generateProject("my-scm", ModuleType.PLATFORM_SERVICE, config, tempDir)
        assertTrue(result.isSuccess)

        val projectDir = result.getOrThrow()
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/commonMain/kotlin/io/example/myscm/MyScmPlatformServiceModule.kt"
                )
            )
        )
    }

    // --- generateProject for MUTATOR type ---

    @Test
    fun `generateProject creates mutator plugin`() {
        val config = TemplateConfig(
            name = "auto-label",
            packageName = "io.example.autolabel",
            type = ModuleType.PLATFORM_SERVICE,
            initGit = false
        )

        val result = generator.generateProject("auto-label", ModuleType.PLATFORM_SERVICE, config, tempDir)
        assertTrue(result.isSuccess)

        val projectDir = result.getOrThrow()
        assertTrue(
            TestFileHelpers.exists(
                Path(
                    projectDir,
                    "src/commonMain/kotlin/io/example/autolabel/AutoLabelPlatformServiceModule.kt"
                )
            )
        )
    }

    @Test
    fun `generateProject creates comment poster plugin`() {
        val config = TemplateConfig(
            name = "my-poster",
            packageName = "io.example.myposter",
            type = ModuleType.PLATFORM_SERVICE,
            initGit = false
        )

        val result = generator.generateProject(
            "my-poster",
            ModuleType.PLATFORM_SERVICE,
            config,
            tempDir
        )
        assertTrue(result.isSuccess)

        val projectDir = result.getOrThrow()
        val module = Path(projectDir, "src/commonMain/kotlin/io/example/myposter/MyPosterPlatformServiceModule.kt")
        assertTrue(TestFileHelpers.exists(module))
        // The comment-poster methods have no SDK default: the generated class does not compile without them.
        val source = TestFileHelpers.readFile(module)
        for (method in listOf("postComment", "updateComment", "findExistingComment", "postInlineComment")) {
            assertTrue("override suspend fun $method(" in source, "$method missing:\n$source")
        }
        assertTrue("\"\$id does not post comments yet\"" in source, source)
    }

    // --- generateProject fails if directory exists ---

    @Test
    fun `generateProject fails if directory already exists`() {
        val config = TemplateConfig(
            name = "existing",
            packageName = "io.example.existing",
            type = ModuleType.RULE,
            initGit = false
        )

        // Create the directory first
        val firstResult = generator.generateProject("existing", ModuleType.RULE, config, tempDir)
        assertTrue(firstResult.isSuccess)

        // Second attempt should fail
        val secondResult = generator.generateProject("existing", ModuleType.RULE, config, tempDir)
        assertTrue(secondResult.isFailure)
    }

    // --- type mismatch ---

    @Test
    fun `generateProject fails on type mismatch`() {
        val config = TemplateConfig(
            name = "test",
            packageName = "io.example.test",
            type = ModuleType.RULE,
            initGit = false
        )

        val result = generator.generateProject("test", ModuleType.REPORTER, config, tempDir)
        assertTrue(result.isFailure)
    }

    // --- Template content verification ---

    @Test
    fun `rule template contains AbstractTypedSingleRuleModule`() {
        val config = TemplateConfig(
            name = "title-check",
            packageName = "io.example.titlecheck",
            type = ModuleType.RULE,
            initGit = false
        )

        val result = generator.generateProject("title-check", ModuleType.RULE, config, tempDir)
        assertTrue(result.isSuccess)

        val sourceContent = TestFileHelpers.readFile(
            Path(
                result.getOrThrow(),
                "src/commonMain/kotlin/io/example/titlecheck/TitleCheckRuleModule.kt"
            )
        )
        assertTrue(sourceContent.contains("AbstractTypedSingleRuleModule"))
        assertTrue(sourceContent.contains("FindingFactory"))
        assertTrue(sourceContent.contains("serializer()"))
        // Rule ids are camelCase: config keys are normalized, so a kebab-case id would never match.
        assertTrue(sourceContent.contains("const val RULE_ID: String = \"titleCheckCheck\""), sourceContent)
    }

    @Test
    fun `settings gradle contains project name`() {
        val config = TemplateConfig(
            name = "my-check",
            packageName = "io.example.mycheck",
            type = ModuleType.RULE,
            initGit = false
        )

        val result = generator.generateProject("my-check", ModuleType.RULE, config, tempDir)
        assertTrue(result.isSuccess)

        val settingsContent = TestFileHelpers.readFile(
            Path(result.getOrThrow(), "settings.gradle.kts")
        )
        assertTrue(settingsContent.contains("my-check-plugin"))
    }

    @Test
    fun `services file contains fully qualified class name`() {
        val config = TemplateConfig(
            name = "my-check",
            packageName = "io.example.mycheck",
            type = ModuleType.RULE,
            initGit = false
        )

        val result = generator.generateProject("my-check", ModuleType.RULE, config, tempDir)
        assertTrue(result.isSuccess)

        val servicesContent = TestFileHelpers.readFile(
            Path(
                result.getOrThrow(),
                "src/jvmMain/resources/META-INF/services/io.karandash.api.Module"
            )
        )
        assertTrue(servicesContent.contains("io.example.mycheck.MyCheckRuleModule"))
    }

    @Test
    fun `readme contains plugin type`() {
        val config = TemplateConfig(
            name = "my-reporter",
            packageName = "io.example.myreporter",
            type = ModuleType.REPORTER,
            initGit = false
        )

        val result = generator.generateProject(
            "my-reporter",
            ModuleType.REPORTER,
            config,
            tempDir
        )
        assertTrue(result.isSuccess)

        val readmeContent = TestFileHelpers.readFile(
            Path(result.getOrThrow(), "README.md")
        )
        assertTrue(readmeContent.contains("reporter"))
        assertTrue(readmeContent.contains("my-reporter-plugin"))
    }

    @Test
    fun `build file pins the host SDK and Kotlin versions and every placeholder is substituted`() {
        val config = TemplateConfig(name = "pinned", packageName = "io.example.pinned", type = ModuleType.RULE)
        val projectDir = generator.generateProject("pinned", ModuleType.RULE, config, tempDir).getOrThrow()

        val build = TestFileHelpers.readFile(Path(projectDir, "build.gradle.kts"))
        assertTrue(build.contains("ru.kode.karandash:sdk:${Version.VERSION}"), build)
        assertTrue(Regex("""kotlin\("multiplatform"\) version "\d+\.\d+\.\d+"""").containsMatchIn(build), build)
        for (file in listOf("build.gradle.kts", "README.md", "src/commonMain/kotlin/io/example/pinned/PinnedRuleModule.kt")) {
            assertFalse(TestFileHelpers.readFile(Path(projectDir, file)).contains("{{"), "$file has an unsubstituted placeholder")
        }
    }
}
