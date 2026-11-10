package io.karandash.registry

import io.karandash.Version
import io.karandash.api.ModuleType
import io.karandash.api.support.suspendCatching
import io.karandash.models.template.Mustache
import io.karandash.models.template.TemplateEscapes
import io.karandash.platform.PlatformProcess
import io.karandash.platform.absolutePath
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createDirectories
import io.karandash.platform.exists
import io.karandash.platform.setExecutable
import io.karandash.platform.writeText
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generates plugin project scaffolding from inlined templates.
 *
 * All templates are sourced from [PluginTemplates] (no resource file I/O).
 * Uses KMP-only APIs for file system operations, process execution, and permissions.
 */
class PluginTemplateGenerator {
    /**
     * Generate a complete plugin project.
     */
    fun generateProject(
        name: String,
        type: ModuleType,
        config: TemplateConfig,
        baseDir: Path = Path(".")
    ): Result<Path> =
        suspendCatching {
            require(type == config.type) {
                "Plugin type mismatch: argument=$type, config.type=${config.type}"
            }
            val projectDir = Path(baseDir, "$name-plugin").absolutePathOrSelf().apply {
                if (this.exists()) {
                    error("Directory $this already exists")
                }
            }

            createDirectoryStructure(projectDir, config).getOrThrow()
            generateBuildFile(projectDir, name).getOrThrow()
            generateSourceFile(projectDir, config).getOrThrow()
            generateTestFile(projectDir, config).getOrThrow()
            generateMetaInfServices(projectDir, config).getOrThrow()
            generateReadme(projectDir, config).getOrThrow()
            generateBuildScript(projectDir).getOrThrow()

            if (config.initGit) {
                initGitRepository(projectDir).getOrThrow()
            }

            // the directory exists now, so the canonical path can be resolved for the caller
            projectDir.absolutePath()
        }

    /**
     * Create directory structure for the plugin project.
     */
    fun createDirectoryStructure(
        basePath: Path,
        config: TemplateConfig
    ): Result<Unit> =
        suspendCatching {
            val packagePath = config.packageName.replace('.', '/')
            createDirectories(Path(basePath, "src/commonMain/kotlin/$packagePath"))
            createDirectories(Path(basePath, "src/jvmMain/resources/META-INF/services"))
            createDirectories(Path(basePath, "src/commonTest/kotlin/$packagePath"))
        }

    /**
     * Generate build.gradle.kts and settings.gradle.kts files.
     */
    fun generateBuildFile(
        path: Path,
        name: String
    ): Result<Unit> =
        suspendCatching {
            Path(path, "settings.gradle.kts").writeText(
                render(PluginTemplates.SETTINGS_GRADLE_KTS, versionValues() + ("PROJECT_NAME" to "$name-plugin"))
            )
            Path(path, "build.gradle.kts").writeText(render(PluginTemplates.BUILD_GRADLE_KTS, versionValues()))
        }

    /**
     * Generate commonMain source file based on plugin type.
     */
    fun generateSourceFile(
        path: Path,
        config: TemplateConfig
    ): Result<Unit> =
        suspendCatching {
            val packagePath = config.packageName.replace('.', '/')
            val className = toCamelCase(config.name) + moduleSuffix(config.type)
            val sourceFile = Path(path, "src/commonMain/kotlin/$packagePath/$className.kt")
            sourceFile.writeText(applySourceTemplate(config))
        }

    /**
     * Generate test file based on plugin type.
     */
    fun generateTestFile(
        path: Path,
        config: TemplateConfig
    ): Result<Unit> =
        suspendCatching {
            val packagePath = config.packageName.replace('.', '/')
            val className = toCamelCase(config.name) + moduleSuffix(config.type)
            val testFile = Path(path, "src/commonTest/kotlin/$packagePath/${className}Test.kt")
            testFile.writeText(applyTestTemplate(config))
        }

    /**
     * Generate META-INF/services entry for ServiceLoader discovery.
     */
    fun generateMetaInfServices(
        path: Path,
        config: TemplateConfig
    ): Result<Unit> =
        suspendCatching {
            val servicesFile = Path(
                path,
                "src/jvmMain/resources/META-INF/services/io.karandash.api.Module"
            )
            val className = toCamelCase(config.name) + moduleSuffix(config.type)
            servicesFile.writeText("${config.packageName}.$className\n")
        }

    /**
     * Generate README.md with quickstart instructions.
     */
    fun generateReadme(
        path: Path,
        config: TemplateConfig
    ): Result<Unit> =
        suspendCatching {
            Path(path, "README.md").writeText(applyReadmeTemplate(config))
        }

    /**
     * Generate an executable `build.sh` that creates the Gradle wrapper on first use and builds the plugin.
     */
    fun generateBuildScript(path: Path): Result<Unit> =
        suspendCatching {
            val script = Path(path, "build.sh")
            script.writeText(render(PluginTemplates.BUILD_SH, versionValues()))
            setExecutable(script)
        }

    /**
     * Initialize a git repository in the project directory.
     */
    fun initGitRepository(path: Path): Result<Unit> =
        suspendCatching {
            val gitDir = Path(path, ".git")
            if (!gitDir.exists()) {
                PlatformProcess.execute(listOf("git", "init"), workingDir = path.toString()).outputOrThrow("git init failed in $path")
            }
            Path(path, ".gitignore").writeText(PluginTemplates.GITIGNORE)
        }

    /**
     * Validate plugin name format.
     */
    fun validatePluginName(name: String): Result<Unit> =
        suspendCatching {
            require(name.isNotEmpty()) { "Plugin name cannot be empty" }
            require(name.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$"))) {
                "Plugin name must be lowercase alphanumeric with hyphens (e.g., 'my-custom-rule')"
            }
        }

    private fun applySourceTemplate(config: TemplateConfig): String {
        val className = toCamelCase(config.name) + moduleSuffix(config.type)
        val template = when (config.type) {
            ModuleType.RULE -> PluginTemplates.RULE_MODULE
            ModuleType.REPORTER -> PluginTemplates.REPORTER_MODULE
            ModuleType.PLATFORM_SERVICE -> PluginTemplates.PROVIDER_MODULE
            else -> error("No source template for module type: ${config.type}")
        }
        return render(template, versionValues() + commonValues(config, className)).trimEnd()
    }

    private fun applyTestTemplate(config: TemplateConfig): String {
        val className = toCamelCase(config.name) + moduleSuffix(config.type)
        val template = when (config.type) {
            ModuleType.RULE -> PluginTemplates.RULE_MODULE_TEST
            ModuleType.REPORTER -> PluginTemplates.REPORTER_MODULE_TEST
            ModuleType.PLATFORM_SERVICE -> PluginTemplates.PROVIDER_MODULE_TEST
            else -> error("No test template for module type: ${config.type}")
        }
        return render(template, versionValues() + commonValues(config, className)).trimEnd()
    }

    private fun applyReadmeTemplate(config: TemplateConfig): String {
        val values =
            versionValues() +
                mapOf(
                    "PROJECT_NAME" to "${config.name}-plugin",
                    "PLUGIN_TYPE" to config.type.cliLabel,
                    "CONFIG_BLOCK" to configBlock(config),
                    "PACKAGE_NAME" to config.packageName,
                    "CLASS_NAME" to toCamelCase(config.name) + moduleSuffix(config.type),
                )
        return render(PluginTemplates.README_MD, values).trimEnd()
    }

    /** Renders a scaffold [template] as [Mustache] with no escaping: generated sources and scripts are not HTML. */
    private fun render(
        template: String,
        values: Map<String, String>,
    ): String = Mustache.render(template, JsonObject(values.mapValues { JsonPrimitive(it.value) }), escape = TemplateEscapes::none)

    private fun versionValues(): Map<String, String> =
        mapOf(
            "KOTLIN_VERSION" to ScaffoldVersions.KOTLIN,
            "COROUTINES_VERSION" to ScaffoldVersions.COROUTINES,
            "GRADLE_VERSION" to ScaffoldVersions.GRADLE,
            "SDK_VERSION" to Version.VERSION,
            "PLUGIN_VERSION" to PLUGIN_VERSION,
        )

    private fun commonValues(
        config: TemplateConfig,
        className: String,
    ): Map<String, String> {
        val camel = toCamelCase(config.name)
        val lowerCamel = camel.replaceFirstChar { it.lowercaseChar() }
        return mapOf(
            "PACKAGE_NAME" to config.packageName,
            "CLASS_NAME" to className,
            "MODULE_ID" to moduleId(config),
            "MODULE_NAME" to moduleName(config),
            "PLUGIN_NAME" to config.name,
            "CAMEL_NAME" to camel,
            // Config keys are normalized to camelCase, so `my-rule-check:` / `my-format:` in YAML reach these ids.
            "FORMAT_ID" to lowerCamel,
            "RULE_ID" to lowerCamel + "Check",
        )
    }

    private fun moduleId(config: TemplateConfig): String = "${config.name}-${config.type.cliLabel}"

    private fun moduleName(config: TemplateConfig): String =
        "${toCamelCase(config.name)} ${config.type.cliLabel.split('-').joinToString(" ") {
            it.replaceFirstChar { c ->
                c.uppercaseChar()
            }
        }}"

    private fun configBlock(config: TemplateConfig): String =
        when (config.type) {
            ModuleType.RULE -> "rules:\n  ${config.name}-check:\n    enabled: true"
            ModuleType.REPORTER -> "reporters:\n  ${config.name}: true"
            ModuleType.PLATFORM_SERVICE -> "platforms:\n  ${moduleId(config)}:\n    base-url: \"https://example.com\""
            else -> error("No config block template for module type: ${config.type}")
        }

    private fun moduleSuffix(type: ModuleType): String =
        when (type) {
            ModuleType.RULE -> "RuleModule"
            ModuleType.REPORTER -> "ReporterModule"
            ModuleType.PLATFORM_SERVICE -> "PlatformServiceModule"
            else -> error("No module suffix for module type: $type")
        }

    /**
     * Convert hyphenated name to CamelCase.
     */
    fun toCamelCase(name: String): String = name.split('-', '_').joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    private companion object {
        /** Initial version of a freshly generated plugin. */
        const val PLUGIN_VERSION = "0.1.0"
    }
}
