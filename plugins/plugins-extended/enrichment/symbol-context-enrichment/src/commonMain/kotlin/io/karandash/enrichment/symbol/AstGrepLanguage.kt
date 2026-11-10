package io.karandash.enrichment.symbol

/**
 * Maps source file extensions to ast-grep language identifiers.
 *
 * These are the language IDs accepted by `ast-grep scan --lang <id>`.
 */
enum class AstGrepLanguage(
    val langId: String,
    val extensions: Set<String>
) {
    KOTLIN("kotlin", setOf("kt", "kts")),
    TYPESCRIPT("typescript", setOf("ts", "tsx")),
    JAVASCRIPT("javascript", setOf("js", "jsx")),
    PYTHON("python", setOf("py")),
    GO("go", setOf("go")),
    ;

    companion object {
        fun fromExtension(ext: String): AstGrepLanguage? = entries.firstOrNull { ext.lowercase() in it.extensions }

        fun fromFilePath(path: String): AstGrepLanguage? {
            val ext = path.substringAfterLast('.', "")
            return if (ext.isEmpty()) null else fromExtension(ext)
        }
    }
}
