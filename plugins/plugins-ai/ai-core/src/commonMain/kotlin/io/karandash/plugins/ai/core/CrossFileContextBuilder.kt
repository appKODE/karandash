package io.karandash.plugins.ai.core

import io.karandash.models.FileChange

/**
 * Builds a cross-file context section for AI prompts by analysing diffs.
 *
 * Parses import statements and declaration signatures from the diff content of
 * each [FileChange] to give the AI model a broader view of the codebase
 * structure beyond the individual files being changed.
 *
 * The output is intended to be appended to the extra-instructions block in an
 * AI prompt so that rules that reason about code relationships (e.g. review
 * comments, risk scoring) have richer context without the caller needing to
 * know anything about import parsing.
 */
object CrossFileContextBuilder {
    /**
     * Default maximum characters for the generated context section (`ai.crossFileContextMaxChars`): a safety
     * ceiling set high enough that `ai.context.budget` binds first.
     */
    const val DEFAULT_MAX_CONTEXT_CHARS = 40_000

    /**
     * Regex patterns for extracting import statements by language.
     *
     * Each pattern captures the imported symbol or path in group 1.
     */
    private val KOTLIN_JAVA_IMPORT = Regex("""^\s*import\s+([\w.]+)""")
    private val TYPESCRIPT_NAMED_IMPORT = Regex("""^\s*import\s*\{[^}]*\}\s*from\s*['"]([^'"]+)['"]""")
    private val TYPESCRIPT_DEFAULT_IMPORT = Regex("""^\s*import\s+\w+\s+from\s*['"]([^'"]+)['"]""")
    private val PYTHON_FROM_IMPORT = Regex("""^\s*from\s+([\w.]+)\s+import\s+""")
    private val PYTHON_IMPORT = Regex("""^\s*import\s+([\w.]+)""")
    private val GO_IMPORT_SINGLE = Regex("""^\s*import\s+"([^"]+)"""")
    private val GO_IMPORT_GROUPED = Regex(""""([^"]+)"""")
    private val C_INCLUDE = Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""")
    private val C_EXTENSIONS = setOf("c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx")

    /**
     * Regex patterns for extracting declaration signatures from added diff lines.
     *
     * Matches lines beginning with common declaration keywords across Kotlin,
     * Java, TypeScript, Python, Go, and Rust.
     */
    private val SIGNATURE_PATTERNS = listOf(
        Regex(
            """^\+\s*((?:data |sealed |abstract |open |internal |private |public |protected )*(?:class|interface|object|enum class)\s+\w[^{(]*)"""
        ),
        Regex("""^\+\s*((?:suspend |inline |internal |private |public |protected |override )*fun\s+\w[^{]*)"""),
        Regex("""^\+\s*((?:async\s+)?def\s+\w[^:]*)"""),
        Regex("""^\+\s*((?:export\s+)?(?:async\s+)?function\s+\w[^{]*)"""),
        Regex("""^\+\s*((?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+\w[^{]*)"""),
        Regex("""^\+\s*(func\s+\w[^{]*)"""),
        Regex("""^\+\s*(type\s+\w+\s+(?:struct|interface)\s*\{?)"""),
        Regex("""^\+\s*((?:pub\s+)?(?:async\s+)?fn\s+\w[^{]*)"""),
    )

    /**
     * C/C++ only ([isCFamily]): `struct`/`union`/`namespace` heads and function definitions with a return type
     * (`static int Foo::bar(int x) const {`). Constructors and multi-line signatures are not matched.
     */
    private val C_SIGNATURE_PATTERNS = listOf(
        Regex("""^\+\s*((?:struct|union|namespace)\s+\w+)"""),
        Regex(
            """^\+\s*(?!(?:if|else|while|for|switch|return|case|do|catch|new|delete|throw|sizeof|goto)\b)""" +
                """((?:[A-Za-z_][\w:<>,]*[\s*&]+)+[A-Za-z_][\w:~]*\s*\([^;{]*\)(?:\s*const)?)\s*\{?\s*$"""
        ),
    )

    /** True for a C or C++ source or header path. */
    fun isCFamily(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in C_EXTENSIONS

    /**
     * Builds a cross-file context string from a list of file changes.
     *
     * Extracts import graphs and declaration signatures from diff content,
     * then formats them into a concise context block. Returns null when there
     * is nothing meaningful to include (all diffs are empty or null).
     *
     * @param changes File changes from the merge request.
     * @param maxChars Maximum number of characters in the returned string.
     * @param importGraph Whether to add the import graph section (`ai.context.importGraph`).
     * @return A formatted context string, or null if no context could be built.
     */
    fun buildContext(
        changes: List<FileChange>,
        maxChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
        importGraph: Boolean = true,
    ): String? {
        if (changes.isEmpty()) return null

        val importsByFile = mutableMapOf<String, List<String>>()
        val signaturesByFile = mutableMapOf<String, List<String>>()

        for (change in changes) {
            val diff = change.diffContent ?: continue
            if (diff.isBlank()) continue

            val imports = if (importGraph) extractImports(diff) else emptyList()
            if (imports.isNotEmpty()) {
                importsByFile[change.path] = imports
            }

            val signatures = extractSignatures(diff, isCFamily(change.path))
            if (signatures.isNotEmpty()) {
                signaturesByFile[change.path] = signatures
            }
        }

        if (importsByFile.isEmpty() && signaturesByFile.isEmpty()) return null

        val changedPaths = changes.map { it.path }.toSet()

        return buildString {
            appendSection("Declared symbols in changed files", signaturesByFile, changedPaths)
            appendSection("Import graph for changed files", importsByFile, changedPaths)
        }.trimEnd().ifEmpty { null }?.take(maxChars)
    }

    /**
     * Extracts import targets from a single diff string.
     *
     * Examines every line (not only added lines) because the import block
     * represents the file's dependency graph regardless of whether the import
     * itself was added or existed before.
     *
     * @param diff Raw unified diff content.
     * @return Deduplicated list of import targets (package names or paths).
     */
    internal fun extractImports(diff: String): List<String> {
        val results = mutableSetOf<String>()
        for (line in diff.lines()) {
            val content = line.removePrefix("+").removePrefix("-").removePrefix(" ")
            C_INCLUDE
                .find(content)
                ?.groupValues
                ?.get(1)
                ?.let { results.add(it) }
                ?: TYPESCRIPT_NAMED_IMPORT
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: TYPESCRIPT_DEFAULT_IMPORT
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: KOTLIN_JAVA_IMPORT
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: PYTHON_FROM_IMPORT
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: PYTHON_IMPORT
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: GO_IMPORT_SINGLE
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { results.add(it) }
                ?: run {
                    if (content.trimStart().startsWith("\"") && GO_IMPORT_GROUPED.containsMatchIn(content)) {
                        GO_IMPORT_GROUPED
                            .find(content)
                            ?.groupValues
                            ?.get(1)
                            ?.let { results.add(it) }
                    }
                }
        }
        return results.toList()
    }

    /**
     * Extracts declaration signatures from added lines (`+` prefix) in a diff.
     *
     * Only added lines are scanned so that the context reflects what is new or
     * changed rather than what already existed in the codebase.
     *
     * @param diff Raw unified diff content.
     * @param cFamily Also match C/C++ declarations ([C_SIGNATURE_PATTERNS]).
     * @return List of signature strings (trimmed, deduplicated).
     */
    internal fun extractSignatures(
        diff: String,
        cFamily: Boolean = false,
    ): List<String> {
        val patterns = if (cFamily) SIGNATURE_PATTERNS + C_SIGNATURE_PATTERNS else SIGNATURE_PATTERNS
        val results = mutableSetOf<String>()
        for (line in diff.lines()) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue
            for (pattern in patterns) {
                val match = pattern.find(line)
                if (match != null) {
                    results.add(match.groupValues[1].trim())
                    break
                }
            }
        }
        return results.toList()
    }

    private fun StringBuilder.appendSection(
        heading: String,
        dataByFile: Map<String, List<String>>,
        changedPaths: Set<String>,
    ) {
        if (dataByFile.isEmpty()) return
        appendLine("#### $heading")
        for ((path, items) in dataByFile) {
            val marker = if (path in changedPaths) " (changed)" else ""
            appendLine("- $path$marker:")
            for (item in items) {
                appendLine("  - $item")
            }
        }
        appendLine()
    }
}
