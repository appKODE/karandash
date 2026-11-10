package io.karandash.cli.commands.stats.audit

import kotlinx.serialization.Serializable

/** MRs per split, after diff-hash dedupe. */
@Serializable
data class Splits(
    val all: List<CorpusMr>,
    val tune: List<CorpusMr>,
    val test: List<CorpusMr>,
    val holdout: List<CorpusMr>,
    val duplicatesDropped: Int,
)

/**
 * Tune/test/holdout split. Tune = created before the cutoff (all MRs without one), test = on or after it,
 * holdout = every MR of the holdout project, which never enters tune or test. [SEED_CASES] are tune-only.
 * A short project name (a seed case's `<name>` or the holdout project) matches an MR whose project path equals it
 * or has it as one of its `/` segments, so `app-foxtrot` matches `app-foxtrot/android` and `app-delta` matches
 * `group/app-delta`.
 */
object AuditSplits {
    /** Hand-mined seed cases (`<project name>!<iid>`); tuning-only so they can't leak into test numbers. */
    val SEED_CASES: Set<String> =
        setOf("app-delta!3082", "app-delta!2885", "app-delta!3253", "app-foxtrot!597", "app-alpha!1948", "app-bravo!697")

    fun split(
        mrs: List<CorpusMr>,
        cutoff: String?,
        holdoutProject: String?,
    ): Splits {
        val seen = mutableSetOf<String>()
        val unique = mrs.filter { it.diffHash.isEmpty() || seen.add(it.diffHash) }
        val isHoldout = { mr: CorpusMr -> holdoutProject != null && matches(mr.project, holdoutProject) }
        // limit: ISO-8601 UTC strings compare lexicographically; switch to Instant parsing if offsets other than Z appear
        val beforeCutoff = { mr: CorpusMr -> cutoff == null || mr.createdAt < cutoff }
        return Splits(
            all = unique,
            tune = unique.filter { isSeed(it) || (!isHoldout(it) && beforeCutoff(it)) },
            test = unique.filter { !isSeed(it) && !isHoldout(it) && cutoff != null && !beforeCutoff(it) },
            holdout = unique.filter { !isSeed(it) && isHoldout(it) },
            duplicatesDropped = mrs.size - unique.size,
        )
    }

    fun isSeed(mr: CorpusMr): Boolean =
        SEED_CASES.any { it.substringAfterLast('!') == mr.iid.toString() && matches(mr.project, it.substringBeforeLast('!')) }

    /** [name] is the whole [project] path or one of its segments. */
    private fun matches(
        project: String,
        name: String,
    ): Boolean = project == name || name in project.split('/')
}
