import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates `GeneratedBuiltInRegistry.kt` from a declarative `builtin-modules.txt`
 * manifest, providing the single source of truth for built-in module factory entries.
 *
 * The manifest format is line-based:
 * - Blank lines and lines starting with `#` are ignored (comments/section headers).
 * - Each non-comment line is a factory expression, e.g.:
 *   - `io.karandash.validators.TitleFormatRuleModule` - simple no-arg constructor
 *   - `io.karandash.sdk.config.ConfigFormatModuleAdapter(io.karandash.plugins.parsers.kts.KtsFormatModule())`
 *     - wrapped constructor (adapter pattern)
 *
 * The task extracts imports from fully qualified class names and produces a
 * `GeneratedBuiltInRegistry.kt` file with a single `builtInFactories` list.
 */
abstract class GenerateBuiltInRegistryTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Package name for the generated file. Defaults to `io.karandash.cli.plugin`. */
    @get:Input
    @get:Optional
    abstract val packageName: Property<String>

    /** Object name for the generated registry. Defaults to `GeneratedBuiltInRegistry`. */
    @get:Input
    @get:Optional
    abstract val objectName: Property<String>

    /** Visibility modifier for the generated object. Defaults to `internal`. */
    @get:Input
    @get:Optional
    abstract val visibility: Property<String>

    @TaskAction
    fun generate() {
        val lines = manifestFile.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val imports = mutableSetOf<String>()
        val factories = mutableListOf<String>()

        for (line in lines) {
            val needsRegistry = line.endsWith("@registry")
            val cleanLine = if (needsRegistry) line.removeSuffix("@registry").trim() else line
            val (factoryExpr, lineImports) = parseFactoryLine(cleanLine, needsRegistry)
            imports.addAll(lineImports)
            if (needsRegistry) {
                factories.add("            { registry -> $factoryExpr }")
            } else {
                factories.add("            { _ -> $factoryExpr }")
            }
        }

        val pkg = packageName.getOrElse("io.karandash.cli.plugin")
        val objName = objectName.getOrElse("GeneratedBuiltInRegistry")
        val vis = visibility.getOrElse("internal")
        val pkgDir = pkg.replace('.', '/')
        val outFile = outputDir.get().asFile.resolve("$pkgDir/$objName.kt")
        outFile.parentFile.mkdirs()

        val content = buildString {
            appendLine("package $pkg")
            appendLine()
            for (imp in imports.sorted()) {
                appendLine("import $imp")
            }
            appendLine("import io.karandash.api.ModuleRegistry")
            appendLine("import io.karandash.api.Module")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated registry of built-in module factories.")
            appendLine(" *")
            appendLine(" * **Do not edit manually.** Modify `builtin-modules.txt` instead and")
            appendLine(" * run the `generateBuiltInRegistry` Gradle task (runs automatically on build).")
            appendLine(" */")
            appendLine("$vis object $objName {")
            appendLine("    val factories: List<(ModuleRegistry) -> Module> = listOf(")
            appendLine(factories.joinToString(",\n"))
            appendLine("    )")
            appendLine("}")
        }

        outFile.writeText(content)
    }

    /**
     * Parses a manifest line into a factory expression (using simple class names)
     * and the set of fully qualified imports needed.
     *
     * Supports:
     * - `com.example.Foo` -> expression `Foo()`, import `com.example.Foo`
     * - `com.example.Wrapper(com.example.Inner())` -> expression `Wrapper(Inner())`,
     *   imports `com.example.Wrapper` and `com.example.Inner`
     *
     * When [needsRegistry] is true, simple class references (no parens) produce
     * `Foo(registry)` instead of `Foo()`.
     */
    private fun parseFactoryLine(line: String, needsRegistry: Boolean = false): Pair<String, Set<String>> {
        val fqcnPattern = Regex("""[a-z][a-zA-Z0-9_.]*\.[A-Z][a-zA-Z0-9]*""")
        val fqcns = fqcnPattern.findAll(line).map { it.value }.toList()
        val imports = fqcns.toSet()

        var expr = line
        // Replace each FQCN with its simple name, longest first to avoid partial replacements
        for (fqcn in fqcns.sortedByDescending { it.length }) {
            val simpleName = fqcn.substringAfterLast('.')
            expr = expr.replace(fqcn, simpleName)
        }

        // Ensure trailing constructor call for simple class references without parens
        if (!expr.contains('(')) {
            expr = if (needsRegistry) "$expr(registry)" else "$expr()"
        }

        return expr to imports
    }
}
