package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.utils.VersionComparator
import io.karandash.utils.finding.FindingFactory

/**
 * Validates dependency files changed in a merge request.
 *
 * Checks added dependency entries from diff content against three rule types:
 * unpinned version detection, minimum version enforcement, and blocked patterns.
 */
class DependencyValidator(
    private val config: DependencyValidationConfig,
    private val unpinnedPrefixes: Set<String> = DEFAULT_UNPINNED_PREFIXES,
    private val unpinnedValues: Set<String> = DEFAULT_UNPINNED_VALUES,
) {
    companion object {
        private val unpinnedWildcardSuffix = Regex("""\.[xX*]$""")
        val DEFAULT_UNPINNED_PREFIXES = setOf("^", "~", ">", ">=", "<", "<=", "!=")
        val DEFAULT_UNPINNED_VALUES = setOf("latest", "*", "x", "X")
    }

    fun validate(mr: MergeRequest): List<Finding> =
        mr.changes
            .filter { DependencyFileParser.isDependencyFile(it.path) }
            .flatMap { change ->
                val entries = DependencyFileParser.parseAddedDependencies(change.path, change.diffContent)
                entries.flatMap { entry -> checkEntry(entry, change.path) }
            }

    private fun checkEntry(
        entry: DependencyEntry,
        filePath: String
    ): List<Finding> =
        buildList {
            if (config.warnOnUnpinnedVersions && isUnpinned(entry.version)) {
                add(
                    FindingFactory.warn(
                        rule = DependencyValidationRuleModule.RULE_ID,
                        message = "Unpinned dependency '${entry.name}': version '${entry.version}' uses a range or wildcard in $filePath",
                        suggestion = "Pin to an exact version to ensure reproducible builds.",
                        explanation = "Unpinned versions can introduce unexpected changes when dependencies are updated.",
                    ),
                )
            }

            val minVersion = config.minVersions[entry.name]
            if (minVersion != null && VersionComparator.isBelow(entry.version, minVersion)) {
                add(
                    FindingFactory.error(
                        rule = DependencyValidationRuleModule.RULE_ID,
                        message = "Dependency '${entry.name}' version '${entry.version}' is below" +
                            " the required minimum '$minVersion' in $filePath",
                        suggestion = "Upgrade to version $minVersion or higher.",
                        explanation = "Older versions may contain known vulnerabilities or lack required features.",
                    ),
                )
            }

            for (pattern in config.blockedPatterns) {
                if (matchesBlockedPattern(entry, pattern)) {
                    add(
                        FindingFactory.error(
                            rule = DependencyValidationRuleModule.RULE_ID,
                            message = "Blocked dependency pattern '$pattern' matched by '${entry.name}:${entry.version}' in $filePath",
                            suggestion = "Remove or replace this dependency.",
                            explanation = "This dependency version matches a configured blocked pattern.",
                        ),
                    )
                    break
                }
            }
        }

    private fun isUnpinned(version: String): Boolean {
        if (version.isBlank()) return false
        if (unpinnedValues.any { version.equals(it, ignoreCase = true) }) return true
        if (version.endsWith("+") || version.contains('$') || unpinnedWildcardSuffix.containsMatchIn(version)) return true
        return unpinnedPrefixes.any { version.startsWith(it) }
    }

    /**
     * Checks if [entry] matches a blocked [pattern].
     *
     * Pattern format: `name`, `name:version-prefix`, `group:artifact` (any version), or
     * `group:artifact:version-prefix`. A two-part pattern equal to a `group:artifact` entry name is
     * read as that coordinate, not as `name:version-prefix`.
     */
    private fun matchesBlockedPattern(
        entry: DependencyEntry,
        pattern: String
    ): Boolean {
        val parts = pattern.split(":")
        return when (parts.size) {
            1 -> {
                entry.name.equals(parts[0], ignoreCase = true)
            }

            2 -> {
                val (pName, pVer) = parts
                entry.name.equals(pattern, ignoreCase = true) ||
                    (entry.name.equals(pName, ignoreCase = true) && entry.version.startsWith(pVer))
            }

            3 -> {
                val pName = "${parts[0]}:${parts[1]}"
                val pVer = parts[2]
                entry.name.equals(pName, ignoreCase = true) &&
                    entry.version.startsWith(pVer)
            }

            else -> {
                false
            }
        }
    }
}
