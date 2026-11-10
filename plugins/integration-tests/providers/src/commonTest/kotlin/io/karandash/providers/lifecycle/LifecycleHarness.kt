package io.karandash.providers.lifecycle

import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.models.DiffRefs
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.outputs.PlatformOutputModule
import io.karandash.testfixtures.TestPluginContext
import io.karandash.translations.russian.RussianTranslationModule
import io.karandash.utils.finding.AnchorFingerprint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Drives the real [PlatformOutputModule] over a [FakeGitLab] one push at a time, as CI runs it per pipeline. */
class LifecycleHarness(
    val gitlab: FakeGitLab = FakeGitLab(),
    private val config: JsonElement = JsonObject(emptyMap()),
) {
    init {
        RussianTranslationModule.register()
    }

    /** The fake's GitLab service, one instance across pushes. */
    suspend fun service() = gitlab.service()

    /** One CI run at head [sha] with [files] reporting [findings]; [changedFiles] empty is a full cache hit. */
    suspend fun push(
        sha: String,
        files: Map<String, String>,
        findings: List<Finding>,
        changedFiles: Set<String>? = null,
    ) {
        gitlab.setHead(files)
        val module = PlatformOutputModule()
        module.initialize(TestPluginContext(pluginConfig = config))
        module.configurePlatformService(service(), "42", "7", DiffRefs(baseSha = "base", headSha = sha, startSha = "base"))
        module.output(report(findings).copy(changedFiles = changedFiles), config)
    }

    companion object {
        const val SUMMARY_MARKER = "<!-- karandash-validation-result -->"
        const val INLINE_MARKER = "<!-- karandash-inline -->"

        /** A finding on [path]:[line] whose anchor fingerprint is that of [lineText], as the engine stamps it. */
        fun finding(
            rule: String,
            path: String,
            line: Int,
            lineText: String,
            message: String,
        ) = Finding(
            severity = Severity.WARNING,
            message = message,
            rule = rule,
            location = Location.FileRef(path, line),
            metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to AnchorFingerprint.generate(rule, path, lineText)),
        )

        /** The markdown format's report for [findings]: one marked inline note per finding. */
        fun report(findings: List<Finding>) =
            FormattedReport(
                formatId = "markdown",
                body = "$SUMMARY_MARKER\n## Review\n${findings.size} finding(s)",
                inlineComments = findings.map {
                    val location = it.location as Location.FileRef
                    InlineCommentData(
                        filePath = location.path,
                        line = checkNotNull(location.line),
                        body = "$INLINE_MARKER\n**⚠️ ${it.severity}** — `${it.rule}`\n\n${it.message}\n${InlineFindingMarker.of(it)}",
                    )
                },
                commentMarker = SUMMARY_MARKER,
                inlineMarker = INLINE_MARKER,
            )
    }
}
