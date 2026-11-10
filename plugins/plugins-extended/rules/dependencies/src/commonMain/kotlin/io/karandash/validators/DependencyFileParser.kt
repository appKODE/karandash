package io.karandash.validators

import kotlinx.serialization.Serializable

/**
 * Extracts added dependency declarations from diff content.
 *
 * Parses `+` lines (excluding `+++` header lines) from unified diffs and
 * identifies dependency name/version pairs based on the file format inferred
 * from the file path.
 */
internal object DependencyFileParser {
    val KNOWN_DEPENDENCY_FILES = setOf(
        "package.json",
        "requirements.txt",
        "Cargo.toml",
        "go.mod",
        "build.gradle",
        "build.gradle.kts",
    )

    val KNOWN_DEPENDENCY_PATHS = setOf("gradle/libs.versions.toml")

    /** Returns true if [path] names a known dependency file. */
    fun isDependencyFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in KNOWN_DEPENDENCY_FILES || KNOWN_DEPENDENCY_PATHS.any { path.endsWith(it) }
    }

    /**
     * Extracts added dependency entries from [diffContent].
     *
     * @param path File path (used to infer the parser).
     * @param diffContent Unified diff string; may be null.
     * @return List of detected [DependencyEntry] items from added lines only.
     */
    fun parseAddedDependencies(
        path: String,
        diffContent: String?
    ): List<DependencyEntry> {
        if (diffContent.isNullOrBlank()) return emptyList()
        val diffLines = diffContent
            .lines()
            .filter { !it.startsWith("+++") && !it.startsWith("---") && !it.startsWith("-") && !it.startsWith("@@") }
            .map { DiffLine(text = it.drop(1).trim(), added = it.startsWith("+")) }
        val addedLines = diffLines.filter { it.added }.map { it.text }
        val name = path.substringAfterLast('/')
        return when {
            name == "package.json" -> parsePackageJson(diffLines)
            name == "requirements.txt" -> parseRequirementsTxt(addedLines)
            name == "Cargo.toml" -> parseCargo(addedLines)
            name == "go.mod" -> parseGoMod(addedLines)
            name == "build.gradle" || name == "build.gradle.kts" -> parseGradle(addedLines)
            name == "libs.versions.toml" -> parseLibsVersions(diffLines)
            else -> emptyList()
        }
    }

    private class DiffLine(
        val text: String,
        val added: Boolean
    )

    private val packageJsonRegex = Regex("""^\s*"([^"]+)"\s*:\s*"([^"]+)"""")
    private val jsonSectionRegex = Regex("""^"([^"]+)"\s*:\s*\{""")
    private val packageJsonDependencySections = setOf("dependencies", "devDependencies", "peerDependencies")

    /**
     * Reads only the dependency blocks: the section is tracked from the block headers in the diff (context and
     * added lines). A hunk that shows no header leaves the section unknown and its entries are accepted.
     */
    private fun parsePackageJson(lines: List<DiffLine>): List<DependencyEntry> {
        var section: String? = null
        val entries = mutableListOf<DependencyEntry>()
        for (line in lines) {
            val header = jsonSectionRegex.find(line.text)
            if (header != null) {
                section = header.groupValues[1]
                continue
            }
            if (line.text.startsWith("}")) section = null
            if (!line.added || (section != null && section !in packageJsonDependencySections)) continue
            val m = packageJsonRegex.find(line.text) ?: continue
            val version = m.groupValues[2]
            if (section == null && m.groupValues[1] in NON_DEPENDENCY_KEYS) continue
            entries += DependencyEntry(name = m.groupValues[1], version = version)
        }
        return entries
    }

    private val NON_DEPENDENCY_KEYS =
        setOf("name", "version", "description", "license", "main", "scripts", "type", "private", "homepage")

    private val requirementsRegex = Regex("""^([a-zA-Z0-9_.-]+)\s*([=!<>~^]{1,3})\s*([\w.*+!-]+)""")

    private fun parseRequirementsTxt(lines: List<String>): List<DependencyEntry> =
        lines.mapNotNull { line ->
            if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
            val m = requirementsRegex.find(line) ?: return@mapNotNull null
            // Include the operator in the version string so unpinned detection works (e.g. ">=2.0").
            DependencyEntry(name = m.groupValues[1], version = m.groupValues[2] + m.groupValues[3])
        }

    private val cargoSimpleRegex = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*"([^"]+)"""")
    private val cargoTableRegex = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*\{[^}]*version\s*=\s*"([^"]+)"""")

    private fun parseCargo(lines: List<String>): List<DependencyEntry> =
        lines.mapNotNull { line ->
            if (line.startsWith("[") || line.startsWith("#")) return@mapNotNull null
            val m = cargoTableRegex.find(line) ?: cargoSimpleRegex.find(line) ?: return@mapNotNull null
            DependencyEntry(name = m.groupValues[1], version = m.groupValues[2])
        }

    private val goModRegex = Regex("""^\s*(?:require\s+)?([^\s]+)\s+v([\d][\w.+-]*)""")

    private fun parseGoMod(lines: List<String>): List<DependencyEntry> =
        lines.mapNotNull { line ->
            val m = goModRegex.find(line) ?: return@mapNotNull null
            DependencyEntry(name = m.groupValues[1], version = m.groupValues[2])
        }

    private val gradleStringDepRegex = Regex("""["']([^"':]+):([^"':]+):([^"':]+)["']""")

    private fun parseGradle(lines: List<String>): List<DependencyEntry> =
        lines.mapNotNull { line ->
            if (line.trimStart().startsWith("//")) return@mapNotNull null
            val m = gradleStringDepRegex.find(line) ?: return@mapNotNull null
            val artifact = "${m.groupValues[1]}:${m.groupValues[2]}"
            DependencyEntry(name = artifact, version = m.groupValues[3])
        }

    private val libsVersionRegex = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*"([^"]+)"""")
    private val tomlSectionRegex = Regex("""^\[([^\]]+)\]""")
    private val libModuleRegex = Regex("""^([a-zA-Z0-9_.-]+)\s*=\s*\{.*module\s*=\s*"([^"]+)"""")
    private val libShorthandRegex = Regex("""^[a-zA-Z0-9_.-]+\s*=\s*"([^":]+:[^":]+):([^":]+)"""")
    private val libVersionRegex = Regex("""version\s*=\s*"([^"]+)"""")
    private val libVersionRefRegex = Regex("""version\.ref\s*=\s*"([^"]+)"""")

    /**
     * Section aware: `[versions]` entries are versions, `[libraries]` entries are `module` coordinates (or the `"group:artifact:version"` shorthand) whose
     * `version.ref` resolves against the `[versions]` lines of the same diff (unresolvable refs are skipped).
     */
    private fun parseLibsVersions(lines: List<DiffLine>): List<DependencyEntry> {
        var section: String? = null
        val versions = mutableMapOf<String, String>()
        val pending = mutableListOf<String>()
        val entries = mutableListOf<DependencyEntry>()
        for (line in lines) {
            val header = tomlSectionRegex.find(line.text)
            if (header != null) {
                section = header.groupValues[1]
                continue
            }
            if (line.text.startsWith("#")) continue
            if (section == "libraries") {
                if (line.added) pending += line.text
                continue
            }
            if (section != null && section != "versions") continue
            val m = libsVersionRegex.find(line.text) ?: continue
            versions[m.groupValues[1]] = m.groupValues[2]
            if (line.added) entries += DependencyEntry(name = m.groupValues[1], version = m.groupValues[2])
        }
        for (text in pending) {
            libShorthandRegex.find(text)?.let {
                entries += DependencyEntry(name = it.groupValues[1], version = it.groupValues[2])
                continue
            }
            val module = libModuleRegex.find(text) ?: continue
            val ref = libVersionRefRegex.find(text)?.groupValues?.get(1)
            val version = if (ref != null) versions[ref] else libVersionRegex.find(text)?.groupValues?.get(1)
            if (version != null) entries += DependencyEntry(name = module.groupValues[2], version = version)
        }
        return entries
    }
}

/**
 * A detected dependency name and version from a dependency file diff.
 *
 * @param name Dependency name as it appears in the file.
 * @param version Version string as it appears in the file.
 */
@Serializable
internal data class DependencyEntry(
    val name: String,
    val version: String,
)
