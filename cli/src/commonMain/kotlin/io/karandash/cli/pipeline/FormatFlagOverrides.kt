package io.karandash.cli.pipeline

import io.karandash.api.platform.CIEnvironment
import io.karandash.crypto.sha1Hex
import io.karandash.crypto.sha256Hex
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.report.MrView
import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.utils.mr.oldLineAt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The keys every format config gets on top of its own: the CLI flags that are on (`quiet`, `verbose`, `explain`,
 * `strict`, `color`), `--style`, and the merge request ([MrView]) the report is about when one was fetched, with
 * line links for the file lines of [findings].
 */
internal fun formatFlagOverrides(
    options: ValidationOptions,
    mr: MergeRequest?,
    findings: List<Finding> = emptyList(),
): JsonObject {
    val flags = listOf(
        "quiet" to options.quiet,
        "verbose" to options.verbose,
        "explain" to options.explain,
        "strict" to options.strict,
        "color" to options.color,
    ).filter { it.second }
        .associate<Pair<String, Boolean>, String, JsonElement> { it.first to JsonPrimitive(true) }
    val style = options.style?.let { mapOf("style" to JsonPrimitive(it)) }.orEmpty()
    val mrView = mr
        ?.let { request ->
            val view = request.toView().let { it.copy(lineLinks = it.url?.let { url -> lineLinks(url, request, findings) }.orEmpty()) }
            mapOf("mr" to KarandashJson.Default.encodeToJsonElement(MrView.serializer(), view))
        }.orEmpty()
    return JsonObject(flags + style + mrView)
}

/**
 * [MergeRequest] as an [MrView]; the URL comes from the CI environment (GitLab merge request pipelines, GitHub
 * Actions) because [MergeRequest] carries none, so a local run has links to nothing.
 */
private fun MergeRequest.toView(): MrView = mrView(iid, title) { PlatformEnvironment.getEnv(it) }

/**
 * The [MrView] of merge request [iid]. The CI environment names the pipeline's own merge request, so its URL is used
 * only when that is [iid]: a file-mode run on another MR (a fixture) gets no link rather than a link to the wrong one.
 */
internal fun mrView(
    iid: Int,
    title: String,
    env: (String) -> String?,
): MrView {
    fun read(name: String) = env(name)?.takeIf { it.isNotBlank() }
    val gitLabProject = read("CI_MERGE_REQUEST_PROJECT_URL")?.takeIf { read("CI_MERGE_REQUEST_IID") == "$iid" }
    val gitHubServer = read("GITHUB_SERVER_URL")
    val gitHubRepo = read("GITHUB_REPOSITORY")
    val gitHubPull = CIEnvironment.pullRequestNumber(read("GITHUB_REF")) == "$iid"
    val url = when {
        gitLabProject != null -> "${gitLabProject.trimEnd('/')}/-/merge_requests/$iid"
        gitHubServer != null && gitHubRepo != null && gitHubPull -> "${gitHubServer.trimEnd('/')}/$gitHubRepo/pull/$iid"
        else -> null
    }
    return MrView(ref = (if (gitLabProject != null) "!" else "#") + iid, title = title, url = url)
}

/**
 * `path:line` of each file-line finding mapped to that line on the changes page of the merge request at [mrUrl].
 * GitHub names a diff line `#diff-<sha256(path)>R<line>`; GitLab `#<sha1(path)>_<old>_<new>`, where `old` is its
 * old-file counter at that line in [mr]'s diff; a line outside the diff's hunks gets GitLab's file anchor `#<sha1(path)>`.
 */
internal fun lineLinks(
    mrUrl: String,
    mr: MergeRequest,
    findings: List<Finding>,
): Map<String, String> {
    val base = mrUrl.trimEnd('/')
    val gitHub = "/pull/" in base
    val changes = mr.changes.associateBy { it.path }
    return findings
        .mapNotNull { it.location as? Location.FileRef }
        .mapNotNull { ref -> ref.line?.let { ref.path to it } }
        .distinct()
        .associate { (path, line) ->
            "$path:$line" to if (gitHub) {
                "$base/files#diff-${sha256Hex(path)}R$line"
            } else {
                val file = sha1Hex(path)
                changes[path]?.oldLineAt(line)?.let { old -> "$base/diffs#${file}_${old}_$line" } ?: "$base/diffs#$file"
            }
        }
}
