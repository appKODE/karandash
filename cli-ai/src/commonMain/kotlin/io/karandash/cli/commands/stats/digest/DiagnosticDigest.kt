package io.karandash.cli.commands.stats.digest

import io.karandash.models.support.KarandashJson
import io.karandash.platform.readText
import io.karandash.platform.safeName
import io.karandash.platform.safeParent
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

/** Weekly numbers over a set of `diagnostic.json` files; a rate is null when nothing counts toward it. */
@Serializable
data class Digest(
    val runs: Int,
    val mergeRequests: Int,
    val redRunRate: Double?,
    val aiRuns: Int,
    val aiAvailability: Double?,
    val fixRate: Double?,
    val fixRateMergeRequests: Int,
    val deadRules: List<String>,
    val aiCostUsd: Double,
    val aiCostPerMergeRequest: Double?,
    val cacheHitRate: Double?,
    val ruleCacheHitRate: Double?,
    val durationP50Ms: Long?,
    val durationP95Ms: Long?,
    val falsePositives: Int,
    val skippedFiles: Int = 0,
)

/** Reads `diagnostic.json` files (a file, a directory walked for `*.json`, or `dir/pattern*.json`) into a [Digest]. */
object DiagnosticDigest {
    private val AI_OUTAGE = setOf("AI_PROVIDER_ERROR", "AI_RATE_LIMITED")
    private const val P50 = 50
    private const val P95 = 95
    private const val PERCENT = 100

    fun files(arg: String): List<Path> {
        val path = Path(arg)
        val meta = SystemFileSystem.metadataOrNull(path)
        return when {
            meta?.isDirectory == true -> {
                walk(path)
            }

            meta != null -> {
                listOf(path)
            }

            else -> {
                val dir = path.safeParent ?: Path(".")
                if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return emptyList()
                SystemFileSystem.list(dir).filter { wildcard(path.safeName, it.safeName) }.sortedBy { it.toString() }
            }
        }
    }

    private fun walk(dir: Path): List<Path> =
        SystemFileSystem.list(dir).sortedBy { it.safeName }.flatMap {
            when {
                SystemFileSystem.metadataOrNull(it)?.isDirectory == true -> walk(it)
                it.safeName.endsWith(".json") -> listOf(it)
                else -> emptyList()
            }
        }

    /** `*` any run of characters, `?` one character; everything else literal. */
    internal fun wildcard(
        pattern: String,
        name: String,
    ): Boolean {
        if (pattern.isEmpty()) return name.isEmpty()
        return when (pattern[0]) {
            '*' -> (0..name.length).any { wildcard(pattern.substring(1), name.substring(it)) }
            '?' -> name.isNotEmpty() && wildcard(pattern.substring(1), name.substring(1))
            else -> name.isNotEmpty() && name[0] == pattern[0] && wildcard(pattern.substring(1), name.substring(1))
        }
    }

    /** Loads [paths]; a file that is not a diagnostic report is left out with a warning line (path and reason). */
    fun load(paths: List<Path>): Pair<List<DigestReport>, List<String>> {
        val warnings = mutableListOf<String>()
        val reports =
            paths.mapNotNull { p ->
                try {
                    KarandashJson.Default.decodeFromString(DigestReport.serializer(), p.readText())
                } catch (e: IllegalArgumentException) {
                    // also SerializationException: not a diagnostic report
                    warnings += "$p: ${e.message?.lineSequence()?.firstOrNull()}"
                    null
                }
            }
        return reports to warnings
    }

    fun compute(
        reports: List<DigestReport>,
        skippedFiles: Int = 0,
    ): Digest {
        val byMr = reports.withIndex().groupBy({ mrKey(it.value, it.index) }, { it.value })
        val aiRuns = reports.filter { r -> r.rules.any(::isAi) }
        val aiCost = reports.sumOf { r -> r.rules.sumOf { it.costUsd ?: 0.0 } }
        val aiMrs = byMr.count { (_, runs) -> runs.any { r -> r.rules.any(::isAi) } }
        val cache = reports.mapNotNull { it.summary?.cache }
        val cacheTotal = cache.sumOf { it.hits + it.misses }
        val ranRules = reports.flatMap { r -> r.rules.filter { it.status == "ok" } }
        val durations = reports.mapNotNull { it.summary?.totalDurationMs?.takeIf { ms -> ms > 0 } }.sorted()
        val fix = fixCounts(byMr.values)
        return Digest(
            runs = reports.size,
            mergeRequests = byMr.size,
            redRunRate = rate(reports.count { it.summary?.findingSummary?.passed == false }, reports.size),
            aiRuns = aiRuns.size,
            aiAvailability = rate(aiRuns.count { r -> r.rules.none { isAi(it) && isOutage(it) } }, aiRuns.size),
            fixRate = rate(fix.first, fix.second),
            fixRateMergeRequests = fix.third,
            deadRules = deadRules(reports),
            aiCostUsd = aiCost,
            aiCostPerMergeRequest = if (aiMrs > 0) aiCost / aiMrs else null,
            cacheHitRate = rate(cache.sumOf { it.hits }, cacheTotal),
            ruleCacheHitRate = rate(ranRules.count { it.fromCache }, ranRules.size),
            durationP50Ms = percentile(durations, P50),
            durationP95Ms = percentile(durations, P95),
            falsePositives = reports.sumOf { it.feedback?.falsePositives ?: 0 },
            skippedFiles = skippedFiles,
        )
    }

    private fun mrKey(
        r: DigestReport,
        index: Int,
    ): String = r.context?.mrUrl ?: r.context?.mrIid?.let { "iid:$it" } ?: "run:$index"

    private fun isAi(rule: DigestRule) = rule.model != null || rule.costUsd != null || rule.category?.startsWith("AI_") == true

    private fun isOutage(rule: DigestRule) = rule.status == "error" || rule.category in AI_OUTAGE

    /**
     * Over merge requests with two or more runs that list their findings: (findings of the first run gone by the last,
     * findings of the first run, merge requests counted). A run lists its findings when it has any or found nothing.
     */
    private fun fixCounts(mrs: Collection<List<DigestReport>>): Triple<Int, Int, Int> {
        val pairs =
            mrs
                .filter { runs -> runs.size > 1 && runs.none { it.findings.isEmpty() && (it.summary?.findingSummary?.total ?: 0) > 0 } }
                .map { runs ->
                    val ordered = runs.sortedBy { r -> (r.snapshots + r.events).minOfOrNull { it.timestampMs } ?: 0 }
                    ordered
                        .first()
                        .findings
                        .map(::findingKey)
                        .toSet() to ordered
                        .last()
                        .findings
                        .map(::findingKey)
                        .toSet()
                }
        return Triple(pairs.sumOf { (before, after) -> (before - after).size }, pairs.sumOf { it.first.size }, pairs.size)
    }

    private fun findingKey(f: DigestFinding) = "${f.rule}|${f.file.orEmpty()}|${f.message}"

    /** Rules some run configured or ran that never produced a finding in any run. */
    private fun deadRules(reports: List<DigestReport>): List<String> {
        val all = reports.flatMap { r -> r.context?.ruleIds.orEmpty() + r.rules.map { it.ruleId } }.toSet()
        val found =
            reports
                .flatMap { r ->
                    r.findings.map { it.rule } +
                        r.rules.filter { it.findingCount > 0 }.map { it.ruleId } +
                        r.summary
                            ?.ruleTimings
                            .orEmpty()
                            .filter { it.findingCount > 0 }
                            .map { it.ruleId }
                }.toSet()
        return (all - found).sorted()
    }

    private fun rate(
        part: Int,
        whole: Int,
    ): Double? = if (whole > 0) part.toDouble() / whole else null

    /** Nearest-rank percentile of an ascending list. */
    internal fun percentile(
        sorted: List<Long>,
        p: Int,
    ): Long? {
        if (sorted.isEmpty()) return null
        val rank = (p * sorted.size + PERCENT - 1) / PERCENT
        return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)]
    }
}
