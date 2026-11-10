package io.karandash.registry

/**
 * Inlined plugin scaffold templates.
 * All templates use {{PLACEHOLDER}} syntax for variable substitution.
 *
 * A literal `$` in generated Kotlin is written as `$S` (`S = "\$"`) to prevent Kotlin string interpolation here.
 */
object PluginTemplates {
    private const val S = "\$"

    val RULE_MODULE: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.models.MergeRequest
        import io.karandash.models.finding.Finding
        import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
        import io.karandash.utils.finding.FindingFactory
        import kotlinx.serialization.Serializable

        @Serializable
        data class {{CAMEL_NAME}}Config(
            val minTitleLength: Int = 10,
        )

        class {{CLASS_NAME}} : AbstractTypedSingleRuleModule<{{CAMEL_NAME}}Config>({{CAMEL_NAME}}Config.serializer()) {
            override val id: String = "{{MODULE_ID}}"
            override val name: String = "{{MODULE_NAME}}"
            override val version: String = "{{PLUGIN_VERSION}}"

            override val ruleId: String = RULE_ID
            override val explanation: String = "Merge request titles must be at least minTitleLength characters long"

            override fun doValidateTyped(mr: MergeRequest, config: {{CAMEL_NAME}}Config): List<Finding> {
                if (mr.title.length >= config.minTitleLength) return emptyList()
                return listOf(
                    FindingFactory.warn(
                        rule = RULE_ID,
                        message = "Title must be at least $S{config.minTitleLength} characters",
                    ),
                )
            }

            companion object {
                const val RULE_ID: String = "{{RULE_ID}}"
            }
        }
    """.trimIndent()

    val RULE_MODULE_TEST: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.api.ModuleType
        import io.karandash.models.MergeRequest
        import io.karandash.models.MergeRequestState
        import io.karandash.models.User
        import kotlinx.coroutines.test.runTest
        import kotlinx.serialization.json.JsonObject
        import kotlin.test.Test
        import kotlin.test.assertEquals
        import kotlin.test.assertTrue

        class {{CLASS_NAME}}Test {
            private val module = {{CLASS_NAME}}()

            @Test
            fun moduleHasCorrectMetadata() {
                assertEquals("{{MODULE_ID}}", module.id)
                assertEquals(ModuleType.RULE, module.type)
            }

            @Test
            fun shortTitleIsFlagged() = runTest {
                val findings = module.validate({{CLASS_NAME}}.RULE_ID, mergeRequest("Short"), JsonObject(emptyMap()))
                assertEquals(listOf({{CLASS_NAME}}.RULE_ID), findings.map { it.rule })
            }

            @Test
            fun adequateTitlePasses() = runTest {
                val findings = module.validate({{CLASS_NAME}}.RULE_ID, mergeRequest("A sufficiently long title"), JsonObject(emptyMap()))
                assertTrue(findings.isEmpty())
            }

            private fun mergeRequest(title: String): MergeRequest = MergeRequest(
                id = "1",
                iid = 1,
                title = title,
                description = "Test description",
                sourceBranch = "feature/test",
                targetBranch = "main",
                author = User(username = "testuser", name = "Test User"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = emptyList(),
            )
        }
    """.trimIndent()

    /**
     * `--type reporter` scaffolds a `FormatModule`: the pipeline renders `reporters:` entries through format and
     * output modules, so a ReporterModule would load but never be called.
     */
    val REPORTER_MODULE: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.api.finding.FormattedReport
        import io.karandash.api.finding.ValidationReport
        import io.karandash.sdk.module.AbstractFormatModule
        import kotlinx.serialization.json.JsonElement

        /** Enable with `reporters: { {{PLUGIN_NAME}}: true }`; the text goes to the console output. */
        class {{CLASS_NAME}} : AbstractFormatModule() {
            override val id: String = "{{MODULE_ID}}"
            override val name: String = "{{MODULE_NAME}}"
            override val version: String = "{{PLUGIN_VERSION}}"
            override val formatId: String = "{{FORMAT_ID}}"

            override suspend fun format(report: ValidationReport, config: JsonElement): FormattedReport {
                val body = buildString {
                    appendLine("Result: $S{if (report.passed) "PASS" else "FAIL"}")
                    appendLine("Errors: $S{report.summary.errors}, Warnings: $S{report.summary.warnings}, Info: $S{report.summary.info}")
                    report.findings.forEach { appendLine("- [$S{it.severity}] $S{it.rule}: $S{it.message}") }
                }
                return FormattedReport(formatId = formatId, body = body.trimEnd())
            }
        }
    """.trimIndent()

    val REPORTER_MODULE_TEST: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.api.ModuleType
        import io.karandash.api.finding.ReportMetadata
        import io.karandash.api.finding.ValidationReport
        import io.karandash.api.finding.summarize
        import io.karandash.models.finding.Finding
        import io.karandash.models.finding.Severity
        import kotlinx.coroutines.test.runTest
        import kotlinx.serialization.json.JsonObject
        import kotlin.test.Test
        import kotlin.test.assertEquals
        import kotlin.test.assertTrue

        class {{CLASS_NAME}}Test {
            private val module = {{CLASS_NAME}}()

            @Test
            fun moduleHasCorrectMetadata() {
                assertEquals("{{MODULE_ID}}", module.id)
                assertEquals(ModuleType.FORMAT, module.type)
                assertEquals("{{FORMAT_ID}}", module.formatId)
            }

            @Test
            fun formatsEmptyFindings() = runTest {
                val body = module.format(report(emptyList()), JsonObject(emptyMap())).body
                assertTrue(body.contains("Result: PASS"), body)
                assertTrue(body.contains("Errors: 0, Warnings: 0, Info: 0"), body)
            }

            @Test
            fun formatsFindings() = runTest {
                val finding = Finding(rule = "test-rule", severity = Severity.ERROR, message = "Test finding", location = null)
                val body = module.format(report(listOf(finding)), JsonObject(emptyMap())).body
                assertTrue(body.contains("Result: FAIL"), body)
                assertTrue(body.contains("Test finding"), body)
            }

            private fun report(findings: List<Finding>) = ValidationReport(
                passed = findings.none { it.severity == Severity.ERROR },
                findings = findings,
                summary = findings.summarize(),
                groupedByLocation = emptyMap(),
                inlineEligible = emptyList(),
                summaryOnly = findings,
                complianceChecklist = emptyList(),
                explanations = emptyMap(),
                metadata = ReportMetadata(version = "test", timestamp = 0),
            )
        }
    """.trimIndent()

    /** One template serves every platform-service flavour (provider, mutator, comment-poster): override what you support. */
    val PROVIDER_MODULE: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.api.PluginContext
        import io.karandash.models.DiffRefs
        import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
        import kotlinx.serialization.Serializable

        @Serializable
        data class {{CAMEL_NAME}}Config(
            val baseUrl: String = "",
        )

        /**
         * Provider and mutator methods (fetchMergeRequestByUrl, addLabels, ...) default to "unsupported" in
         * [AbstractTypedPlatformServiceModule]; override the ones your platform implements.
         * The comment-poster methods below have no default: replace the stubs with real API calls.
         */
        class {{CLASS_NAME}} : AbstractTypedPlatformServiceModule<{{CAMEL_NAME}}Config>({{CAMEL_NAME}}Config.serializer()) {
            override val id: String = "{{MODULE_ID}}"
            override val name: String = "{{MODULE_NAME}}"
            override val version: String = "{{PLUGIN_VERSION}}"

            override suspend fun initializeTyped(context: PluginContext, config: {{CAMEL_NAME}}Config): Boolean = true

            override suspend fun postComment(projectId: String, mrId: String, body: String): String =
                throw UnsupportedOperationException("${S}id does not post comments yet")

            override suspend fun updateComment(projectId: String, mrId: String, commentId: String, body: String): Unit =
                throw UnsupportedOperationException("${S}id does not update comments yet")

            override suspend fun findExistingComment(projectId: String, mrId: String, marker: String): String? = null

            override suspend fun postInlineComment(
                projectId: String,
                mrId: String,
                body: String,
                filePath: String,
                line: Int,
                diffRefs: DiffRefs,
                startLine: Int?,
            ): String = throw UnsupportedOperationException("${S}id does not post inline comments yet")
        }
    """.trimIndent()

    val PROVIDER_MODULE_TEST: String = """
        package {{PACKAGE_NAME}}

        import io.karandash.api.ModuleType
        import kotlin.test.Test
        import kotlin.test.assertEquals

        class {{CLASS_NAME}}Test {
            @Test
            fun moduleHasCorrectMetadata() {
                val module = {{CLASS_NAME}}()
                assertEquals("{{MODULE_ID}}", module.id)
                assertEquals(ModuleType.PLATFORM_SERVICE, module.type)
            }
        }
    """.trimIndent()

    val BUILD_GRADLE_KTS: String = """
        import org.jetbrains.kotlin.gradle.dsl.JvmTarget

        plugins {
            kotlin("multiplatform") version "{{KOTLIN_VERSION}}"
            kotlin("plugin.serialization") version "{{KOTLIN_VERSION}}"
        }

        version = "{{PLUGIN_VERSION}}"

        repositories {
            mavenLocal()
            // Tag releases publish the plugin SDK (ru.kode.karandash:sdk) to Maven Central.
            mavenCentral()
        }

        kotlin {
            jvm {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
            sourceSets {
                commonMain.dependencies {
                    // The karandash host provides the SDK (and kotlinx-serialization / coroutines) at runtime,
                    // so the plugin JAR must not bundle it: compileOnly, not implementation.
                    compileOnly("ru.kode.karandash:sdk:{{SDK_VERSION}}")
                }
                commonTest.dependencies {
                    implementation("ru.kode.karandash:sdk:{{SDK_VERSION}}")
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:{{COROUTINES_VERSION}}")
                    implementation(kotlin("test"))
                }
            }
        }

        tasks.named<Jar>("jvmJar") {
            archiveFileName.set("$S{rootProject.name}.jar")
            manifest {
                attributes(
                    "Plugin-Id" to rootProject.name,
                    "Plugin-Name" to rootProject.name,
                    "Plugin-Version" to project.version,
                )
            }
        }
    """.trimIndent()

    val SETTINGS_GRADLE_KTS: String = """
        rootProject.name = "{{PROJECT_NAME}}"
    """.trimIndent()

    val README_MD: String = """
        # {{PROJECT_NAME}}

        A Karandash {{PLUGIN_TYPE}} plugin.

        ## Building

        Requires JDK 17+ and Gradle {{GRADLE_VERSION}}. The Karandash plugin SDK {{SDK_VERSION}}
        (`ru.kode.karandash:sdk`) comes from Maven Central, which every `vX.Y.Z` release publishes to.
        A local SDK build (`publishSdkToMavenLocal` in the karandash repository) is picked up first.

        ```bash
        gradle wrapper --gradle-version {{GRADLE_VERSION}}   # once, to create ./gradlew
        ./gradlew build
        ```

        The plugin JAR is written to `build/libs/{{PROJECT_NAME}}.jar`.

        ## Installation

        ```bash
        karandash plugin install build/libs/{{PROJECT_NAME}}.jar
        ```

        ## Usage

        Add to your `.karandash/config.yaml`:

        ```yaml
        {{CONFIG_BLOCK}}
        ```

        ## Development

        The module class is `{{PACKAGE_NAME}}.{{CLASS_NAME}}` (`src/commonMain/kotlin`), registered for
        `ServiceLoader` in `src/jvmMain/resources/META-INF/services/io.karandash.api.Module`.

        ```bash
        ./gradlew jvmTest
        ```
    """.trimIndent()

    val BUILD_SH: String = """
        #!/bin/sh
        # Builds the plugin JAR, creating the Gradle wrapper first when it is missing.
        set -e
        [ -f ./gradlew ] || gradle wrapper --gradle-version {{GRADLE_VERSION}}
        exec ./gradlew build "$@"
    """.trimIndent() + "\n"

    val GITIGNORE: String = """
        # Gradle
        .gradle/
        build/
        .kotlin/

        # IDE
        .idea/
        *.iml
        .vscode/

        # OS
        .DS_Store
    """.trimIndent()
}
