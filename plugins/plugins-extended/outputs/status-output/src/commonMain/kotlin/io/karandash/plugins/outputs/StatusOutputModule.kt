package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultAnnotation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.karandash.api.platform.ResultStatusPublisher
import io.karandash.api.platform.warnUnsupported
import io.karandash.helpers.output.AiRunStatus
import io.karandash.helpers.output.NotificationState
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.helpers.reporter.RdDiagnostic
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.DiffRefs
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.AbstractTypedOutputModule
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable

/**
 * Config for [StatusOutputModule].
 *
 * @property name Name of the check run, commit status, Code Insights report or PR status (`karandash` by default).
 * @property detailsUrl Link shown with the status, e.g. the CI job URL; none by default.
 * @property onError `warn` (default) only logs a status that could not be published; `fail` also fails the output.
 * @property notificationState This run's verdict, set by the pipeline; without it the verdict is "no error findings".
 * @property aiStatus What the AI review did, set by the pipeline when AI is configured; published as `<name>/ai`.
 * @property language Language of the verdict title and summary; injected from `report.language`.
 */
@Serializable
data class StatusOutputConfig(
    val name: String = "karandash",
    val detailsUrl: String? = null,
    val onError: OnError = OnError.WARN,
    val notificationState: NotificationState? = null,
    val aiStatus: AiRunStatus? = null,
    val language: String? = null,
) : ReporterConfig

/**
 * Publishes the verdict, with the findings as annotations where the platform has them, as the platform's own result
 * status ([ResultStatusPublisher]): a GitHub check run, a GitLab commit status, a Bitbucket Code Insights report or an
 * Azure DevOps PR status. Opt-in (`reporters: { status: {} }`); a platform without one logs a warning and skips.
 *
 * Takes the `rdjsonl` format, one diagnostic per line, so it reads file, line and severity without parsing prose.
 *
 * @lifecycle per-run -- the platform context is set once per pipeline invocation.
 */
class StatusOutputModule : AbstractTypedOutputModule<StatusOutputConfig>(StatusOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Status Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val description: String = "Publishes the verdict as a check run, commit status, Code Insights report or PR status."
    override val requiresPlatformContext: Boolean = true
    override val acceptedFormats: Set<String> = setOf(RDJSONL)
    override val preferredFormat: String = RDJSONL
    override val scope: ModuleScope get() = ModuleScope.PER_RUN

    private var logger: Logger = NoOpLogger
    private val platformService = atomic<PlatformServiceModule?>(null)
    private val projectId = atomic<String?>(null)
    private val mrId = atomic<String?>(null)
    private val diffRefs = atomic<DiffRefs?>(null)

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun newHandle(context: PluginContext): Module = StatusOutputModule()

    override suspend fun shutdown() {
        platformService.value = null
        projectId.value = null
        mrId.value = null
        diffRefs.value = null
    }

    override fun configurePlatformContext(
        platformService: PlatformServiceModule,
        projectId: String,
        mrId: String,
        diffRefs: DiffRefs?,
        diffLineRanges: Map<String, Set<Int>>,
    ) {
        this.platformService.value = platformService
        this.projectId.value = projectId
        this.mrId.value = mrId
        this.diffRefs.value = diffRefs
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: StatusOutputConfig,
    ) {
        val platform = platformService.value
        val project = projectId.value
        val mr = mrId.value
        if (platform == null || project == null || mr == null) {
            logger.warn("Status output skipped: no platform context configured")
            return
        }
        if (platform !is ResultStatusPublisher || !platform.supports(PlatformOperation.RESULT_STATUS)) {
            logger.warnUnsupported(platform.name, "Status output")
            return
        }
        val headSha = diffRefs.value?.headSha
        if (headSha == null) {
            logger.warn("Status output skipped: the MR head commit is unknown")
            return
        }
        config.onError.handle(logger, "Failed to publish the result status") {
            platform.publishResultStatus(project, mr, resultStatus(report.body, config, headSha))
        }
        val ai = config.aiStatus ?: return
        // A provider outage is its own status, so the MR verdict stays about the code
        config.onError.handle(logger, "Failed to publish the AI status") {
            platform.publishResultStatus(
                project,
                mr,
                ResultStatus("${config.name}/ai", ai.passed, ai.line, ai.line, headSha, detailsUrl = config.detailsUrl),
            )
        }
    }

    private fun resultStatus(
        body: String,
        config: StatusOutputConfig,
        headSha: String,
    ): ResultStatus {
        val diagnostics = body
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { KarandashJson.Lenient.decodeFromString(RdDiagnostic.serializer(), it) }
            .toList()
        val errors = diagnostics.count { it.severity == ERROR }
        val warnings = diagnostics.count { it.severity == WARNING }
        val passed = config.notificationState?.passed ?: (errors == 0)
        val language = config.language
        val counts = "${StatusText.ERRORS.count(language, errors)}, ${StatusText.WARNINGS.count(language, warnings)}"
        val title = "${(if (passed) StatusText.PASSED else StatusText.FAILED).of(language)}: $counts"
        return ResultStatus(
            name = config.name,
            passed = passed,
            title = title,
            summary = StatusText.SUMMARY.of(
                language,
                StatusText.ISSUES.count(language, diagnostics.size),
                StatusText.ERRORS.count(language, errors),
                StatusText.WARNINGS.count(language, warnings),
            ),
            headSha = headSha,
            annotations = diagnostics.mapNotNull(::annotation),
            detailsUrl = config.detailsUrl,
        )
    }

    private fun annotation(diagnostic: RdDiagnostic): ResultAnnotation? {
        val location = diagnostic.location ?: return null
        val line = location.range?.start?.line ?: return null
        val level = when (diagnostic.severity) {
            ERROR -> ResultAnnotationLevel.FAILURE
            WARNING -> ResultAnnotationLevel.WARNING
            else -> ResultAnnotationLevel.NOTICE
        }
        return ResultAnnotation(
            path = location.path,
            line = line,
            endLine = location.range?.end?.line,
            level = level,
            title = diagnostic.code?.value ?: "karandash",
            message = diagnostic.message,
        )
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.status"

        /** Output identifier: `reporters.status`. */
        const val OUTPUT_ID = "status"

        private const val RDJSONL = "rdjsonl"
        private const val ERROR = "ERROR"
        private const val WARNING = "WARNING"
    }
}
