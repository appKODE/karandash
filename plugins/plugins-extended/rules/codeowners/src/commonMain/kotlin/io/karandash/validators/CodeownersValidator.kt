package io.karandash.validators

import io.karandash.api.support.suspendCatching
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.platform.canRead
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.exists
import io.karandash.platform.isFile
import io.karandash.platform.readText
import io.karandash.utils.GlobMatcher
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.validation.toContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

class CodeownersValidator(
    private val config: CodeownersConfig,
    private val codeownersReader: (String) -> String? = ::readCodeownersFile,
) {
    /** Paths [codeownersReader] is asked for, in lookup order. */
    fun candidatePaths(): List<String> = candidatePaths(config.path)

    fun validate(mr: MergeRequest): List<Finding> {
        if (!config.requireOwnerApproval) {
            return emptyList()
        }

        val candidates = candidatePaths(config.path)
        val content = candidates.firstNotNullOfOrNull { codeownersReader(it) }
            ?: return listOf(unreadableFinding("no CODEOWNERS file found (looked in ${candidates.joinToString(", ")})"))
        val codeownersRules = parseCodeowners(content)

        if (codeownersRules.isEmpty()) {
            return emptyList()
        }

        val ctx = mr.toContext()
        val ownersPerFile = findOwnersPerFile(ctx.changedPaths, codeownersRules)

        if (ownersPerFile.isEmpty()) {
            return emptyList()
        }

        // Only an APPROVED review counts; CHANGES_REQUESTED/COMMENTED/DISMISSED/PENDING reviews do not.
        val normalizedApprovers = ctx.approverNames.map { normalizeOwner(it) }.toSet()
        // Per file, any one owner of the last matching rule satisfies it (GitHub/GitLab semantics).
        val missingOwners = ownersPerFile
            .filter { owners -> owners.none { owner -> ownerMatchCandidates(owner).any { it in normalizedApprovers } } }
            .flatten()
            .distinct()

        return buildList {
            if (missingOwners.isNotEmpty()) {
                add(
                    FindingFactory.error(
                        rule = CodeownersRuleModule.RULE_ID,
                        message = "Missing approval from code owners: ${missingOwners.joinToString(", ")}",
                        suggestion = "Request review from the listed code owners.",
                        explanation = "Code owners review changes in areas they maintain."
                    )
                )
            }
        }
    }

    private fun parseCodeowners(content: String): List<CodeownerRule> =
        content
            .lineSequence()
            .mapNotNull { parseCodeownersLine(it) }
            .toList()

    /** The default path also covers the standard locations GitHub/GitLab read, in their lookup order. */
    private fun candidatePaths(path: String) = if (path == CodeownersConfig.DEFAULT_PATH) DEFAULT_LOCATIONS else listOf(path)

    private fun parseCodeownersLine(rawLine: String): CodeownerRule? {
        val line = stripComment(rawLine).trim()
        if (line.isEmpty()) return null

        val parts = line.split(WHITESPACE_REGEX)
        if (parts.size < 2) return null

        val pattern = parts.first().replace("\\#", "#")
        val owners = parts.drop(1).filter { it.isNotBlank() }
        if (owners.isEmpty()) return null

        return CodeownerRule(
            pattern = pattern,
            owners = owners,
        )
    }

    /** Cuts the line at the first `#` that is not escaped as `\#`. */
    private fun stripComment(line: String): String {
        var i = 0
        while (i < line.length) {
            if (line[i] == '#' && (i == 0 || line[i - 1] != '\\')) return line.substring(0, i)
            i++
        }
        return line
    }

    /** One owner list per changed file: the owners of the last rule that matches it (files without a match are skipped). */
    private fun findOwnersPerFile(
        changedFiles: Set<String>,
        rules: List<CodeownerRule>
    ): List<List<String>> =
        changedFiles.mapNotNull { file ->
            // Last matching rule wins
            rules.lastOrNull { rule -> matchesPattern(file, rule) }?.owners
        }

    private fun matchesPattern(
        file: String,
        rule: CodeownerRule
    ): Boolean = GlobMatcher.codeownersMatches(file, rule.pattern)

    private fun ownerMatchCandidates(owner: String): Set<String> {
        val normalized = normalizeOwner(owner)
        val simple = normalized.substringAfterLast('/')
        return if (simple == normalized) {
            setOf(normalized)
        } else {
            setOf(normalized, simple)
        }
    }

    private fun normalizeOwner(owner: String): String = owner.removePrefix("@").trim().lowercase()

    @Serializable
    private data class CodeownerRule(
        val pattern: String,
        val owners: List<String>,
    )

    companion object {
        /** The warning reported when owner approval is required but CODEOWNERS cannot be read ([reason] says why). */
        fun unreadableFinding(reason: String): Finding =
            FindingFactory.warn(
                rule = CodeownersRuleModule.RULE_ID,
                message = "Code owner approval was not checked: $reason.",
                suggestion = "Add a CODEOWNERS file, point 'codeowners.path' at it, or turn the rule off.",
                explanation = "Without a readable CODEOWNERS file the rule cannot tell who owns the changed files.",
            )

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DEFAULT_LOCATIONS = listOf(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS", ".gitlab/CODEOWNERS")

        private fun readCodeownersFile(path: String): String? {
            val direct = Path(path)
            val candidates = if (path.startsWith("/")) {
                listOf(direct)
            } else {
                listOf(Path(currentWorkingDirectory(), path), direct)
            }

            val file = candidates.firstOrNull { it.exists() && it.isFile() && canRead(it) } ?: return null
            return suspendCatching { file.readText() }.getOrNull()
        }
    }
}
